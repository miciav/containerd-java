package io.nanofaas.containerd.internal;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.nanofaas.containerd.ContainerAlreadyExistsException;
import io.nanofaas.containerd.ContainerSpec;
import io.nanofaas.containerd.ImageNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainersServiceImplTest {

    // Fake services: Images returns an image whose config is a scratch rootfs (no layers),
    // Snapshots records prepare/remove, Containers can be set to fail.
    private static final String SCRATCH_CONFIG = """
            {"rootfs": {"type": "layers", "diff_ids": []}}
            """;
    private static final String SCRATCH_MANIFEST = """
            {
              "mediaType": "application/vnd.oci.image.manifest.v1+json",
              "config": {"mediaType": "application/vnd.oci.image.config.v1+json", "digest": "sha256:config", "size": 40},
              "layers": []
            }
            """;

    private static final class FakeServer implements AutoCloseable {
        final AtomicInteger snapshotsPrepared = new AtomicInteger();
        final AtomicInteger snapshotsRemoved = new AtomicInteger();
        final AtomicReference<containerd.services.containers.v1.Container> created = new AtomicReference<>();
        volatile boolean failContainerCreate;

        final io.grpc.Server server;
        final io.grpc.ManagedChannel channel;

        FakeServer() throws Exception {
            String name = InProcessServerBuilder.generateName();
            var builder = InProcessServerBuilder.forName(name).directExecutor();

            builder.addService(containerd.services.images.v1.ImagesGrpc.bindService(
                    new containerd.services.images.v1.ImagesGrpc.ImagesImplBase() {
                        @Override
                        public void get(containerd.services.images.v1.GetImageRequest request,
                                        StreamObserver<containerd.services.images.v1.GetImageResponse> responseObserver) {
                            responseObserver.onNext(containerd.services.images.v1.GetImageResponse.newBuilder()
                                    .setImage(containerd.services.images.v1.Image.newBuilder()
                                            .setName(request.getName())
                                            .setTarget(containerd.types.Descriptor.newBuilder()
                                                    .setDigest("sha256:manifest")
                                                    .setSize(SCRATCH_MANIFEST.length())))
                                    .build());
                            responseObserver.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.content.v1.ContentGrpc.bindService(
                    new containerd.services.content.v1.ContentGrpc.ContentImplBase() {
                        @Override
                        public void info(containerd.services.content.v1.InfoRequest request,
                                         StreamObserver<containerd.services.content.v1.InfoResponse> responseObserver) {
                            byte[] data = request.getDigest().equals("sha256:config")
                                    ? SCRATCH_CONFIG.getBytes() : SCRATCH_MANIFEST.getBytes();
                            responseObserver.onNext(containerd.services.content.v1.InfoResponse.newBuilder()
                                    .setInfo(containerd.services.content.v1.Info.newBuilder()
                                            .setDigest(request.getDigest()).setSize(data.length)).build());
                            responseObserver.onCompleted();
                        }

                        @Override
                        public void read(containerd.services.content.v1.ReadContentRequest request,
                                         StreamObserver<containerd.services.content.v1.ReadContentResponse> responseObserver) {
                            byte[] data = request.getDigest().equals("sha256:config")
                                    ? SCRATCH_CONFIG.getBytes() : SCRATCH_MANIFEST.getBytes();
                            responseObserver.onNext(containerd.services.content.v1.ReadContentResponse.newBuilder()
                                    .setOffset(0).setData(com.google.protobuf.ByteString.copyFrom(data)).build());
                            responseObserver.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.snapshots.v1.SnapshotsGrpc.bindService(
                    new containerd.services.snapshots.v1.SnapshotsGrpc.SnapshotsImplBase() {
                        @Override
                        public void prepare(containerd.services.snapshots.v1.PrepareSnapshotRequest request,
                                            StreamObserver<containerd.services.snapshots.v1.PrepareSnapshotResponse> responseObserver) {
                            snapshotsPrepared.incrementAndGet();
                            responseObserver.onNext(containerd.services.snapshots.v1.PrepareSnapshotResponse.getDefaultInstance());
                            responseObserver.onCompleted();
                        }

                        @Override
                        public void remove(containerd.services.snapshots.v1.RemoveSnapshotRequest request,
                                           StreamObserver<com.google.protobuf.Empty> responseObserver) {
                            snapshotsRemoved.incrementAndGet();
                            responseObserver.onNext(com.google.protobuf.Empty.getDefaultInstance());
                            responseObserver.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.containers.v1.ContainersGrpc.bindService(
                    new containerd.services.containers.v1.ContainersGrpc.ContainersImplBase() {
                        @Override
                        public void create(containerd.services.containers.v1.CreateContainerRequest request,
                                           StreamObserver<containerd.services.containers.v1.CreateContainerResponse> responseObserver) {
                            if (failContainerCreate) {
                                responseObserver.onError(Status.ALREADY_EXISTS.asRuntimeException());
                                return;
                            }
                            created.set(request.getContainer());
                            responseObserver.onNext(containerd.services.containers.v1.CreateContainerResponse.newBuilder()
                                    .setContainer(request.getContainer()).build());
                            responseObserver.onCompleted();
                        }
                    }));

            server = builder.build().start();
            channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        }

        @Override
        public void close() {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    private static ContainersServiceImpl service(FakeServer fake) {
        return new ContainersServiceImpl(fake.channel, "overlayfs", "io.containerd.runc.v2", null);
    }

    private static ContainerSpec spec() {
        return ContainerSpec.builder().id("test-1").image("scratch:latest")
                .command(java.util.List.of("/bin/sh")).build();
    }

    @Test
    void createPreparesSnapshotAndCreatesContainerWithGcLabel() throws Exception {
        try (var fake = new FakeServer()) {
            service(fake).create(spec());

            assertThat(fake.snapshotsPrepared.get()).isEqualTo(1);
            var container = fake.created.get();
            assertThat(container.getId()).isEqualTo("test-1");
            assertThat(container.getSnapshotter()).isEqualTo("overlayfs");
            assertThat(container.getSnapshotKey()).isEqualTo("test-1");
            assertThat(container.getRuntime().getName()).isEqualTo("io.containerd.runc.v2");
            assertThat(container.getLabelsMap())
                    .containsEntry("containerd.io/gc.ref.snapshot.overlayfs", "test-1");
            assertThat(container.getSpec().getTypeUrl())
                    .isEqualTo("types.containerd.io/opencontainers/runtime-spec/1/Spec");
        }
    }

    @Test
    void userLabelsCannotOverwriteTheGcSnapshotReference() throws Exception {
        try (var fake = new FakeServer()) {
            // A user label on the reserved GC key must not win: losing that reference would let
            // containerd collect the snapshot out from under a live container.
            service(fake).create(ContainerSpec.builder().id("test-1").image("scratch:latest")
                    .labels(java.util.Map.of(
                            "containerd.io/gc.ref.snapshot.overlayfs", "hijacked",
                            "app", "nanofaas"))
                    .build());

            assertThat(fake.created.get().getLabelsMap())
                    .containsEntry("containerd.io/gc.ref.snapshot.overlayfs", "test-1")
                    .containsEntry("app", "nanofaas");
        }
    }

    @Test
    void createFailureRemovesPreparedSnapshotAndMapsException() throws Exception {
        try (var fake = new FakeServer()) {
            fake.failContainerCreate = true;
            assertThatThrownBy(() -> service(fake).create(spec()))
                    .isInstanceOf(ContainerAlreadyExistsException.class);
            assertThat(fake.snapshotsPrepared.get()).isEqualTo(1);
            assertThat(fake.snapshotsRemoved.get()).isEqualTo(1);
        }
    }
}
