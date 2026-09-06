package io.nanofaas.containerd.internal;

import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.nanofaas.containerd.Version;
import io.nanofaas.containerd.spi.ContainerdClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the R12 wiring end to end: a client built through the public API
 * (builder -&gt; DefaultContainerdClient -&gt; GrpcChannelFactory) carries the namespace header
 * on EVERY call, and (R16) that {@link ContainerdClient#close()} fully releases the transport's
 * event loop threads. A real Netty epoll UDS server on a temp socket serves the Version RPC;
 * no test-only seams, no /run/containerd access needed.
 */
class DefaultContainerdClientWiringTest {

    private static final Metadata.Key<String> NS_KEY =
            Metadata.Key.of("containerd-namespace", Metadata.ASCII_STRING_MARSHALLER);

    /** A real epoll UDS Version server; boss/worker groups use distinct thread names so tests
     *  that scan for the client transport's "epollEventLoopGroup" threads are not confused. */
    private static final class VersionServer implements AutoCloseable {
        private final Path socket;
        private final Server server;
        private final EpollEventLoopGroup boss;
        private final EpollEventLoopGroup worker;

        VersionServer(Path socketDir, AtomicReference<Metadata> captured) throws IOException {
            this.socket = socketDir.resolve("containerd.sock");
            this.boss = new EpollEventLoopGroup(1, new DefaultThreadFactory("it-uds-server-boss"));
            this.worker = new EpollEventLoopGroup(1, new DefaultThreadFactory("it-uds-server-worker"));
            try {
                ServerServiceDefinition service = ServerInterceptors.intercept(
                        containerd.services.version.v1.VersionGrpc.bindService(
                                new containerd.services.version.v1.VersionGrpc.VersionImplBase() {
                                    @Override
                                    public void version(com.google.protobuf.Empty request,
                                                        StreamObserver<containerd.services.version.v1.VersionResponse> responseObserver) {
                                        responseObserver.onNext(containerd.services.version.v1.VersionResponse.newBuilder()
                                                .setVersion("1.7.0")
                                                .setRevision("abc123")
                                                .build());
                                        responseObserver.onCompleted();
                                    }
                                }),
                        new ServerInterceptor() {
                            // gRPC's own type-parameter names, as in ServerInterceptor.
                            @SuppressWarnings("java:S119")
                            @Override
                            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                                    ServerCall<ReqT, RespT> call,
                                    Metadata headers,
                                    ServerCallHandler<ReqT, RespT> next) {
                                captured.set(new Metadata());
                                captured.get().merge(headers);
                                return next.startCall(call, headers);
                            }
                        });
                this.server = NettyServerBuilder.forAddress(new DomainSocketAddress(socket.toString()))
                        .channelType(EpollServerDomainSocketChannel.class)
                        .bossEventLoopGroup(boss)
                        .workerEventLoopGroup(worker)
                        .addService(service)
                        .build()
                        .start();
            } catch (IOException e) {
                boss.shutdownGracefully().syncUninterruptibly();
                worker.shutdownGracefully().syncUninterruptibly();
                throw e;
            }
        }

        Path socket() {
            return socket;
        }

        @Override
        public void close() {
            server.shutdownNow();
            boss.shutdownGracefully().syncUninterruptibly();
            worker.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Test
    void versionCallCarriesNamespaceHeaderOnRealUdsChannel(@TempDir Path tempDir) throws Exception {
        AtomicReference<Metadata> captured = new AtomicReference<>();
        try (VersionServer server = new VersionServer(tempDir, captured)) {
            try (ContainerdClient client = ContainerdClient.builder()
                    .socketPath(server.socket().toString())
                    .namespace("nanofaas-wiring")
                    .build()) {
                Version version = client.version();
                assertThat(version.version()).isEqualTo("1.7.0");
                assertThat(version.revision()).isEqualTo("abc123");
            }
        }
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().get(NS_KEY)).isEqualTo("nanofaas-wiring");
    }

    @Test
    void closeReleasesTransportEventLoopThreads(@TempDir Path tempDir) throws Exception {
        AtomicReference<Metadata> captured = new AtomicReference<>();
        try (VersionServer server = new VersionServer(tempDir, captured)) {
            ContainerdClient client = ContainerdClient.builder()
                    .socketPath(server.socket().toString())
                    .namespace("nanofaas-leak")
                    .build();
            try {
                client.version(); // real RPC: the transport's event loop group spawns threads now
                assertThat(liveNonDaemonThreadNames("epollEventLoopGroup"))
                        .as("transport event loop threads must be live while the client is open")
                        .isNotEmpty();
            } finally {
                client.close();
            }
            // server groups shut down synchronously above; anything left named
            // "epollEventLoopGroup" can only belong to the client's transport (or a leak).
            assertThat(awaitNoLiveNonDaemonThreads("epollEventLoopGroup", Duration.ofSeconds(5)))
                    .as("close() must release all transport event loop threads")
                    .isTrue();
        }
    }

    private static List<String> liveNonDaemonThreadNames(String nameContains) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && !t.isDaemon() && t.getName().contains(nameContains))
                .map(Thread::getName)
                .sorted()
                .toList();
    }

    // Polls for threads to be gone, which means sleeping between looks: there is no notification
    // for "this thread has finally exited", only the absence of it in the next sample.
    @SuppressWarnings("java:S2925")
    private static boolean awaitNoLiveNonDaemonThreads(String nameContains, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (liveNonDaemonThreadNames(nameContains).isEmpty()) {
                return true;
            }
            Thread.sleep(50);
        }
        return liveNonDaemonThreadNames(nameContains).isEmpty();
    }
}
