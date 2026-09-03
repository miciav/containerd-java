package io.nanofaas.containerd.internal;

import com.google.protobuf.Timestamp;
import io.nanofaas.containerd.ContainerState;
import io.nanofaas.containerd.Platform;
import io.nanofaas.containerd.Signal;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtoMapperTest {

    @Test
    void mapsProtoContainer() {
        var proto = containerd.services.containers.v1.Container.newBuilder()
                .setId("abc-123")
                .setImage("docker.io/library/alpine:latest")
                .setSnapshotter("overlayfs")
                .setSnapshotKey("abc-123")
                .setCreatedAt(Timestamp.newBuilder().setSeconds(1700000000).build())
                .putLabels("a", "b")
                .build();

        var container = ProtoMapper.map(proto);

        assertThat(container.id()).isEqualTo("abc-123");
        assertThat(container.image()).isEqualTo("docker.io/library/alpine:latest");
        assertThat(container.snapshotKey()).isEqualTo("abc-123");
        assertThat(container.createdAt()).isEqualTo(Instant.ofEpochSecond(1700000000));
        assertThat(container.labels()).containsEntry("a", "b");
    }

    @Test
    void mapsProtoImage() {
        var proto = containerd.services.images.v1.Image.newBuilder()
                .setName("docker.io/library/alpine:latest")
                .setTarget(containerd.types.Descriptor.newBuilder().setDigest("sha256:abcdef").setSize(1234).build())
                .build();

        var image = ProtoMapper.map(proto);

        assertThat(image.name()).isEqualTo("docker.io/library/alpine:latest");
        assertThat(image.digest()).isEqualTo("sha256:abcdef");
        assertThat(image.size()).isEqualTo(1234L);
    }

    @Test
    void vendoredStatusEnumNumbersAlignWithSwitch() {
        // mapStatus switches on raw enum numbers, so the vendored numbers are part of the contract
        assertThat(containerd.v1.types.Status.CREATED.getNumber()).isEqualTo(1);
        assertThat(containerd.v1.types.Status.RUNNING.getNumber()).isEqualTo(2);
        assertThat(containerd.v1.types.Status.STOPPED.getNumber()).isEqualTo(3);
        assertThat(containerd.v1.types.Status.PAUSED.getNumber()).isEqualTo(4);
        assertThat(containerd.v1.types.Status.PAUSING.getNumber()).isEqualTo(5);

        assertThat(ProtoMapper.mapStatus(containerd.v1.types.Status.CREATED.getNumber())).isEqualTo(ContainerState.CREATED);
        assertThat(ProtoMapper.mapStatus(containerd.v1.types.Status.PAUSED.getNumber())).isEqualTo(ContainerState.PAUSED);
        assertThat(ProtoMapper.mapStatus(containerd.v1.types.Status.PAUSING.getNumber())).isEqualTo(ContainerState.PAUSING);
    }

    @Test
    void mapsTaskStatusEnum() {
        assertThat(ProtoMapper.mapStatus(containerd.v1.types.Status.RUNNING.getNumber())).isEqualTo(ContainerState.RUNNING);
        assertThat(ProtoMapper.mapStatus(containerd.v1.types.Status.STOPPED.getNumber())).isEqualTo(ContainerState.STOPPED);
        assertThat(ProtoMapper.mapStatus(999)).isEqualTo(ContainerState.UNKNOWN);
    }

    @Test
    void rejectsInvalidContainerIds() {
        assertThat(ProtoMapper.requireValidId("abc_1.2-3")).isEqualTo("abc_1.2-3");
        assertThatThrownBy(() -> ProtoMapper.requireValidId("has space")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProtoMapper.requireValidId("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void platformAndSignalDefaults() {
        assertThat(Platform.linuxAmd64().os()).isEqualTo("linux");
        assertThat(Platform.linuxAmd64().architecture()).isEqualTo("amd64");
        assertThat(Signal.TERM.number()).isEqualTo(15);
        assertThat(Signal.KILL.number()).isEqualTo(9);
    }
}
