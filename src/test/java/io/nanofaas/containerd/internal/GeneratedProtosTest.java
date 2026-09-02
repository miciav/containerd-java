package io.nanofaas.containerd.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedProtosTest {
    @Test
    void keyServicesAndMessagesGenerated() {
        // service stubs
        assertThat(containerd.services.version.v1.VersionGrpc.class).isNotNull();
        assertThat(containerd.services.containers.v1.ContainersGrpc.class).isNotNull();
        assertThat(containerd.services.tasks.v1.TasksGrpc.class).isNotNull();
        assertThat(containerd.services.snapshots.v1.SnapshotsGrpc.class).isNotNull();
        assertThat(containerd.services.images.v1.ImagesGrpc.class).isNotNull();
        assertThat(containerd.services.events.v1.EventsGrpc.class).isNotNull();
        assertThat(containerd.services.transfer.v1.TransferGrpc.class).isNotNull();
        assertThat(containerd.services.content.v1.ContentGrpc.class).isNotNull();
        // messages used by later tasks.
        // Upstream v2.2.1 protos declare no java_multiple_files/java_package, so protoc emits
        // one file-named outer class per .proto with messages nested inside (proto package
        // == java package); the java_package-free package names below are verbatim upstream.
        assertThat(containerd.types.MountOuterClass.Mount.class).isNotNull(); // mount.proto
        assertThat(containerd.types.Event.Envelope.class).isNotNull(); // event.proto
        assertThat(containerd.v1.types.Task.Process.class).isNotNull(); // task/task.proto, proto package containerd.v1.types in v2.2.1
        assertThat(containerd.types.transfer.Registry.OCIRegistry.class).isNotNull(); // transfer/registry.proto
        assertThat(containerd.types.transfer.Imagestore.ImageStore.class).isNotNull(); // transfer/imagestore.proto
        assertThat(containerd.events.Task.TaskStart.class).isNotNull(); // events/task.proto
        assertThat(containerd.events.Task.TaskDelete.class).isNotNull(); // events/task.proto
    }
}
