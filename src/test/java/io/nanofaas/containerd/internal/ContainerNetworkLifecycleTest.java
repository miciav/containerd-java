package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.nanofaas.containerd.ContainerSpec;
import io.nanofaas.containerd.ContainerStartException;
import io.nanofaas.containerd.ContainerdException;
import io.nanofaas.containerd.RemoveOptions;
import io.nanofaas.containerd.spi.ContainerNetwork;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * When the network is attached and detached, relative to the task's life.
 *
 * <p>The ordering is the whole point. A container's network namespace is its task's, so it exists
 * only between start and teardown; attaching too early or detaching too late finds nothing there
 * and leaves an address allocated on the host that nothing will ever reclaim. These tests record
 * the order of every call rather than just that each happened.
 */
class ContainerNetworkLifecycleTest {

    private static final int PID = 4242;

    /** Records what it was asked to do, in order, and can be told to fail. */
    private static final class RecordingNetwork implements ContainerNetwork {
        final List<String> events;
        volatile boolean failAttach;
        volatile boolean failDetach;

        RecordingNetwork(List<String> events) {
            this.events = events;
        }

        @Override
        public void attach(String containerId, String network, int pid) {
            events.add("attach:" + network + ":" + pid);
            if (failAttach) {
                throw new IllegalStateException("no address left in the pool");
            }
        }

        @Override
        public void detach(String containerId, String network, int pid) {
            events.add("detach:" + network + ":" + pid);
            if (failDetach) {
                throw new IllegalStateException("plugin exploded");
            }
        }
    }

    private static final String SCRATCH_CONFIG = """
            {"rootfs": {"type": "layers", "diff_ids": []}}
            """;
    private static final String SCRATCH_MANIFEST = """
            {"mediaType": "application/vnd.oci.image.manifest.v1+json",
             "config": {"digest": "sha256:config", "size": 40}, "layers": []}
            """;

    /** A containerd that remembers the container it was given, and records task calls in order. */
    private static final class FakeContainerd implements AutoCloseable {
        final io.grpc.Server server;
        final ManagedChannel channel;
        final List<String> events = new CopyOnWriteArrayList<>();
        volatile containerd.services.containers.v1.Container stored;
        volatile containerd.v1.types.Status taskStatus = containerd.v1.types.Status.RUNNING;
        volatile boolean taskExists;

        FakeContainerd() throws Exception {
            String name = InProcessServerBuilder.generateName();
            var builder = InProcessServerBuilder.forName(name).directExecutor();

            builder.addService(containerd.services.images.v1.ImagesGrpc.bindService(
                    new containerd.services.images.v1.ImagesGrpc.ImagesImplBase() {
                        @Override
                        public void get(containerd.services.images.v1.GetImageRequest request,
                                        StreamObserver<containerd.services.images.v1.GetImageResponse> o) {
                            o.onNext(containerd.services.images.v1.GetImageResponse.newBuilder()
                                    .setImage(containerd.services.images.v1.Image.newBuilder()
                                            .setName(request.getName())
                                            .setTarget(containerd.types.Descriptor.newBuilder()
                                                    .setDigest("sha256:manifest")
                                                    .setSize(SCRATCH_MANIFEST.length())))
                                    .build());
                            o.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.content.v1.ContentGrpc.bindService(
                    new containerd.services.content.v1.ContentGrpc.ContentImplBase() {
                        private String blob(String d) {
                            return d.equals("sha256:config") ? SCRATCH_CONFIG : SCRATCH_MANIFEST;
                        }

                        @Override
                        public void info(containerd.services.content.v1.InfoRequest request,
                                         StreamObserver<containerd.services.content.v1.InfoResponse> o) {
                            o.onNext(containerd.services.content.v1.InfoResponse.newBuilder()
                                    .setInfo(containerd.services.content.v1.Info.newBuilder()
                                            .setDigest(request.getDigest())
                                            .setSize(blob(request.getDigest()).length())).build());
                            o.onCompleted();
                        }

                        @Override
                        public void read(containerd.services.content.v1.ReadContentRequest request,
                                         StreamObserver<containerd.services.content.v1.ReadContentResponse> o) {
                            o.onNext(containerd.services.content.v1.ReadContentResponse.newBuilder()
                                    .setData(com.google.protobuf.ByteString.copyFromUtf8(
                                            blob(request.getDigest()))).build());
                            o.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.snapshots.v1.SnapshotsGrpc.bindService(
                    new containerd.services.snapshots.v1.SnapshotsGrpc.SnapshotsImplBase() {
                        @Override
                        public void prepare(containerd.services.snapshots.v1.PrepareSnapshotRequest request,
                                            StreamObserver<containerd.services.snapshots.v1.PrepareSnapshotResponse> o) {
                            o.onNext(containerd.services.snapshots.v1.PrepareSnapshotResponse
                                    .getDefaultInstance());
                            o.onCompleted();
                        }

                        @Override
                        public void mounts(containerd.services.snapshots.v1.MountsRequest request,
                                           StreamObserver<containerd.services.snapshots.v1.MountsResponse> o) {
                            o.onNext(containerd.services.snapshots.v1.MountsResponse.newBuilder()
                                    .addMounts(containerd.types.Mount.newBuilder().setType("bind")).build());
                            o.onCompleted();
                        }

                        @Override
                        public void remove(containerd.services.snapshots.v1.RemoveSnapshotRequest request,
                                           StreamObserver<com.google.protobuf.Empty> o) {
                            o.onNext(com.google.protobuf.Empty.getDefaultInstance());
                            o.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.containers.v1.ContainersGrpc.bindService(
                    new containerd.services.containers.v1.ContainersGrpc.ContainersImplBase() {
                        @Override
                        public void create(containerd.services.containers.v1.CreateContainerRequest request,
                                           StreamObserver<containerd.services.containers.v1.CreateContainerResponse> o) {
                            stored = request.getContainer();
                            o.onNext(containerd.services.containers.v1.CreateContainerResponse.newBuilder()
                                    .setContainer(stored).build());
                            o.onCompleted();
                        }

                        @Override
                        public void get(containerd.services.containers.v1.GetContainerRequest request,
                                        StreamObserver<containerd.services.containers.v1.GetContainerResponse> o) {
                            if (stored == null) {
                                o.onError(Status.NOT_FOUND.asRuntimeException());
                                return;
                            }
                            o.onNext(containerd.services.containers.v1.GetContainerResponse.newBuilder()
                                    .setContainer(stored).build());
                            o.onCompleted();
                        }

                        @Override
                        public void delete(containerd.services.containers.v1.DeleteContainerRequest request,
                                           StreamObserver<com.google.protobuf.Empty> o) {
                            o.onNext(com.google.protobuf.Empty.getDefaultInstance());
                            o.onCompleted();
                        }
                    }));

            builder.addService(containerd.services.tasks.v1.TasksGrpc.bindService(
                    new containerd.services.tasks.v1.TasksGrpc.TasksImplBase() {
                        @Override
                        public void create(containerd.services.tasks.v1.CreateTaskRequest request,
                                           StreamObserver<containerd.services.tasks.v1.CreateTaskResponse> o) {
                            taskExists = true;
                            events.add("task-create");
                            o.onNext(containerd.services.tasks.v1.CreateTaskResponse.getDefaultInstance());
                            o.onCompleted();
                        }

                        @Override
                        public void start(containerd.services.tasks.v1.StartRequest request,
                                          StreamObserver<containerd.services.tasks.v1.StartResponse> o) {
                            events.add("task-start");
                            o.onNext(containerd.services.tasks.v1.StartResponse.newBuilder()
                                    .setPid(PID).build());
                            o.onCompleted();
                        }

                        @Override
                        public void get(containerd.services.tasks.v1.GetRequest request,
                                        StreamObserver<containerd.services.tasks.v1.GetResponse> o) {
                            if (!taskExists) {
                                o.onError(Status.NOT_FOUND.asRuntimeException());
                                return;
                            }
                            o.onNext(containerd.services.tasks.v1.GetResponse.newBuilder()
                                    .setProcess(containerd.v1.types.Process.newBuilder()
                                            .setContainerId(request.getContainerId())
                                            .setPid(PID).setStatus(taskStatus))
                                    .build());
                            o.onCompleted();
                        }

                        @Override
                        public void kill(containerd.services.tasks.v1.KillRequest request,
                                         StreamObserver<com.google.protobuf.Empty> o) {
                            events.add("kill:" + request.getSignal());
                            o.onNext(com.google.protobuf.Empty.getDefaultInstance());
                            o.onCompleted();
                        }

                        @Override
                        public void wait(containerd.services.tasks.v1.WaitRequest request,
                                         StreamObserver<containerd.services.tasks.v1.WaitResponse> o) {
                            o.onNext(containerd.services.tasks.v1.WaitResponse.newBuilder()
                                    .setExitStatus(0).build());
                            o.onCompleted();
                        }

                        @Override
                        public void delete(containerd.services.tasks.v1.DeleteTaskRequest request,
                                           StreamObserver<containerd.services.tasks.v1.DeleteResponse> o) {
                            taskExists = false;
                            events.add("task-delete");
                            o.onNext(containerd.services.tasks.v1.DeleteResponse.getDefaultInstance());
                            o.onCompleted();
                        }
                    }));

            this.server = builder.build().start();
            this.channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        }

        @Override
        public void close() throws Exception {
            channel.shutdownNow();
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static ContainersServiceImpl service(FakeContainerd fake, ContainerNetwork network) {
        return new ContainersServiceImpl(fake.channel, "overlayfs", "io.containerd.runc.v2", null,
                java.time.Duration.ofSeconds(1), network);
    }

    private static ContainerSpec networked() {
        return ContainerSpec.builder().id("net-1").image("scratch:latest").network("mynet").build();
    }

    @Test
    void attachesOnlyOnceTheTaskIsRunningAndWithItsPid() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            var containers = service(fake, net);
            containers.create(networked());

            containers.start("net-1");

            assertThat(fake.events)
                    .as("the namespace to attach to does not exist until the task does")
                    .containsExactly("task-create", "task-start", "attach:mynet:" + PID);
        }
    }

    @Test
    void detachesBeforeAnythingIsKilled() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            var containers = service(fake, net);
            containers.create(networked());
            containers.start("net-1");
            fake.events.clear();

            containers.stop("net-1");

            assertThat(fake.events).isNotEmpty();
            assertThat(fake.events.get(0))
                    .as("detaching after the kill would find no namespace and leak the address")
                    .isEqualTo("detach:mynet:" + PID);
            assertThat(fake.events).contains("kill:15");
        }
    }

    @Test
    void aFailedAttachTearsTheTaskDownRatherThanLeavingItUnreachable() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            net.failAttach = true;
            var containers = service(fake, net);
            containers.create(networked());

            assertThatThrownBy(() -> containers.start("net-1"))
                    .isInstanceOf(ContainerStartException.class)
                    .hasMessageContaining("mynet");

            assertThat(fake.events)
                    .as("detach runs first: a plugin that failed part-way may already hold an address")
                    .containsSubsequence("attach:mynet:" + PID, "detach:mynet:" + PID, "task-delete");
        }
    }

    @Test
    void aFailingDetachDoesNotBreakTheStopItWasPartOf() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            net.failDetach = true;
            var containers = service(fake, net);
            containers.create(networked());
            containers.start("net-1");

            // The caller is already tearing down; a networking failure must not replace that.
            assertThat(containers.stop("net-1")).isPresent();
            assertThat(fake.events).contains("kill:15");
        }
    }

    @Test
    void anAlreadyExitedTaskIsStillDetached() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            var containers = service(fake, net);
            containers.create(networked());
            containers.start("net-1");
            fake.taskStatus = containerd.v1.types.Status.STOPPED;
            fake.events.clear();

            containers.remove("net-1", RemoveOptions.builder().build());

            assertThat(fake.events)
                    .as("its namespace is gone, but the address it held is not")
                    .contains("detach:mynet:-1");
        }
    }

    @Test
    void aContainerWithoutANetworkIsLeftAlone() throws Exception {
        try (var fake = new FakeContainerd()) {
            var net = new RecordingNetwork(fake.events);
            var containers = service(fake, net);
            containers.create(ContainerSpec.builder().id("net-1").image("scratch:latest").build());

            containers.start("net-1");
            containers.stop("net-1");

            assertThat(fake.events).noneMatch(e -> e.startsWith("attach") || e.startsWith("detach"));
        }
    }

    @Test
    void askingForANetworkWithoutAnImplementationIsRefusedAtCreate() throws Exception {
        try (var fake = new FakeContainerd()) {
            // Refused rather than ignored: a container that asked to be on a network and silently
            // is not is worse than one that never started.
            var containers = service(fake, null);
            var spec = networked();

            assertThatThrownBy(() -> containers.create(spec))
                    .isInstanceOf(ContainerdException.class)
                    .hasMessageContaining("ContainerNetwork");
        }
    }

    @Test
    void theNetworkIsRememberedOnTheContainerNotInMemory() throws Exception {
        try (var fake = new FakeContainerd()) {
            service(fake, new RecordingNetwork(fake.events)).create(networked());

            assertThat(fake.stored.getLabelsMap())
                    .as("detaching can happen from a different process than the one that attached")
                    .containsEntry("io.nanofaas.containerd/cni.network", "mynet");
        }
    }

    @Test
    void hostNetworkAndANetworkCannotBothBeAsked() {
        assertThatThrownBy(() -> ContainerSpec.builder().id("net-1").image("scratch:latest")
                .hostNetwork(true).network("mynet").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutually exclusive");
    }
}
