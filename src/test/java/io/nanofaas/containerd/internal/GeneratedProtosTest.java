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
        // messages used by later tasks (vendored protos are patched with
        // option java_multiple_files = true; by scripts/vendor-protos.sh, so protoc
        // emits flat classes named after the messages, package = proto package)
        assertThat(containerd.types.Mount.class).isNotNull();
        assertThat(containerd.types.Envelope.class).isNotNull();
        assertThat(containerd.v1.types.Process.class).isNotNull(); // task/task.proto: proto package is containerd.v1.types at v2.2.1
        assertThat(containerd.types.transfer.OCIRegistry.class).isNotNull();
        assertThat(containerd.types.transfer.ImageStore.class).isNotNull();
        assertThat(containerd.events.TaskStart.class).isNotNull();
        assertThat(containerd.events.TaskDelete.class).isNotNull();
    }
}
