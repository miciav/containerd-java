package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ContentStoreReaderTest {

    /** A content store whose Info advertises {@code size} and whose Read yields {@code payload}. */
    private static final class FakeContent implements AutoCloseable {
        final io.grpc.Server server;
        final ManagedChannel channel;

        FakeContent(long advertisedSize, byte[] payload, int chunkSize) throws Exception {
            String name = InProcessServerBuilder.generateName();
            this.server = InProcessServerBuilder.forName(name).directExecutor()
                    .addService(containerd.services.content.v1.ContentGrpc.bindService(
                            new containerd.services.content.v1.ContentGrpc.ContentImplBase() {
                                @Override
                                public void info(containerd.services.content.v1.InfoRequest request,
                                                 StreamObserver<containerd.services.content.v1.InfoResponse> o) {
                                    o.onNext(containerd.services.content.v1.InfoResponse.newBuilder()
                                            .setInfo(containerd.services.content.v1.Info.newBuilder()
                                                    .setDigest(request.getDigest())
                                                    .setSize(advertisedSize))
                                            .build());
                                    o.onCompleted();
                                }

                                @Override
                                public void read(containerd.services.content.v1.ReadContentRequest request,
                                                 StreamObserver<containerd.services.content.v1.ReadContentResponse> o) {
                                    int offset = (int) request.getOffset();
                                    while (offset < payload.length) {
                                        int end = Math.min(offset + chunkSize, payload.length);
                                        o.onNext(containerd.services.content.v1.ReadContentResponse.newBuilder()
                                                .setOffset(offset)
                                                .setData(ByteString.copyFrom(payload, offset, end - offset))
                                                .build());
                                        offset = end;
                                    }
                                    o.onCompleted();
                                }
                            }))
                    .build().start();
            this.channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        }

        @Override
        public void close() throws Exception {
            channel.shutdownNow();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void readsABlobDeliveredInASingleChunk() throws Exception {
        byte[] payload = "hello content store".getBytes(StandardCharsets.UTF_8);
        try (var fake = new FakeContent(payload.length, payload, payload.length)) {
            assertThat(new ContentStoreReader(fake.channel).read("sha256:x")).isEqualTo(payload);
        }
    }

    @Test
    void reassemblesABlobDeliveredInSeveralChunks() throws Exception {
        byte[] payload = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        try (var fake = new FakeContent(payload.length, payload, 3)) {
            assertThat(new ContentStoreReader(fake.channel).read("sha256:x")).isEqualTo(payload);
        }
    }

    @Test
    void failsInsteadOfSpinningWhenReadDeliversNothing() throws Exception {
        // Info says 100 bytes, Read completes with no chunks (blob truncated or collected between
        // the two calls). The read loop must not re-issue the same request forever.
        try (var fake = new FakeContent(100, new byte[0], 8)) {
            var reader = new ContentStoreReader(fake.channel);
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertThatThrownBy(() -> reader.read("sha256:truncated"))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("no data")
                            .hasMessageContaining("sha256:truncated"));
        }
    }

    @Test
    void failsInsteadOfSpinningWhenReadStopsShortOfTheAdvertisedSize() throws Exception {
        // Read delivers some bytes then completes early: the loop makes one more attempt, gets
        // nothing (the server resumes from the requested offset), and must give up.
        byte[] partial = "half".getBytes(StandardCharsets.UTF_8);
        try (var fake = new FakeContent(64, partial, 8)) {
            var reader = new ContentStoreReader(fake.channel);
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertThatThrownBy(() -> reader.read("sha256:short"))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("expected 64 bytes"));
        }
    }
}
