package io.nanofaas.containerd;

import io.nanofaas.containerd.spi.ContainerdClient;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class ContainerCreateIT extends ContainerdConnectionIT {

    private static final String ALPINE = "docker.io/library/alpine:latest";

    @BeforeAll
    static void ensureImage() {
        client.images().pull(ALPINE);
    }

    @Test
    @Timeout(60)
    void createsInspectsListsAndRemovesContainer() {
        String id = "it-create-" + UUID.randomUUID();
        try {
            Container container = client.containers().create(
                    ContainerSpec.builder().id(id).image(ALPINE)
                            .command(java.util.List.of("/bin/sh", "-c", "sleep 3600")).build());

            assertThat(container.id()).isEqualTo(id);
            assertThat(container.snapshotter()).isEqualTo(client.snapshotter());

            ContainerStatus status = client.containers().inspect(id);
            assertThat(status.id()).isEqualTo(id);
            assertThat(status.image()).isEqualTo(ALPINE);

            assertThat(client.containers().list()).extracting(Container::id).contains(id);
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).build());
        }
    }

    @Test
    void createWithUnknownImageThrowsImageNotFound() {
        String id = "it-create-" + UUID.randomUUID();
        assertThatThrownBy(() -> client.containers().create(
                ContainerSpec.builder().id(id).image("it-unknown-" + UUID.randomUUID() + ":latest").build()))
                .isInstanceOf(ImageNotFoundException.class);
    }

    @Test
    void duplicateContainerIdThrowsAlreadyExists() {
        String id = "it-dup-" + UUID.randomUUID();
        var spec = ContainerSpec.builder().id(id).image(ALPINE)
                .command(java.util.List.of("/bin/sh", "-c", "sleep 3600")).build();
        try {
            client.containers().create(spec);
            assertThatThrownBy(() -> client.containers().create(spec))
                    .isInstanceOf(ContainerAlreadyExistsException.class);
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).build());
        }
    }
}
