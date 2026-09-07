package io.nanofaas.containerd;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class ContainerLifecycleIT extends ContainerdConnectionIT {

    private static final String ALPINE = "docker.io/library/alpine:latest";

    @BeforeAll
    static void ensureImage() {
        client.images().pull(ALPINE);
    }

    @Test
    @Timeout(120)
    void fullLifecycleCreateStartInspectStopRemove() {
        String id = "it-life-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done")).build());
        try {
            int pid = client.containers().start(id);
            assertThat(pid).isPositive();

            ContainerStatus running = client.containers().inspect(id);
            assertThat(running.state()).isEqualTo(ContainerState.RUNNING);
            assertThat(running.pid()).isPositive();

            Optional<ExitStatus> stopped = client.containers().stop(id);
            assertThat(stopped).isPresent();

            // second stop is idempotent: no task -> empty Optional
            assertThat(client.containers().stop(id)).isEmpty();

            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).build());
            assertThatThrownBy(() -> client.containers().inspect(id))
                    .isInstanceOf(ContainerNotFoundException.class);
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }

    @Test
    @Timeout(120)
    void startAlreadyRunningThrows() {
        String id = "it-already-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done")).build());
        try {
            client.containers().start(id);
            assertThatThrownBy(() -> client.containers().start(id))
                    .isInstanceOf(ContainerStartException.class);
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }

    @Test
    @Timeout(120)
    void removeRunningContainerRequiresForce() {
        String id = "it-running-rm-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done")).build());
        try {
            client.containers().start(id);
            assertThatThrownBy(() -> client.containers().remove(id, RemoveOptions.builder().build()))
                    .isInstanceOf(ContainerdException.class)
                    .hasMessageContaining("still running");
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }

    @Test
    @Timeout(60)
    void startUnknownContainerThrowsNotFound() {
        assertThatThrownBy(() -> client.containers().start("it-unknown-" + UUID.randomUUID()))
                .isInstanceOf(ContainerNotFoundException.class);
    }

    @Test
    @Timeout(120)
    void stopExitsCleanlyWithStatus() {
        String id = "it-exit-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .command(List.of("/bin/sh", "-c", "exit 7")).build());
        try {
            client.containers().start(id);
            ExitStatus status = client.containers().wait(id);
            assertThat(status.code()).isEqualTo(7);
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }

    @Test
    @Timeout(180)
    void runsUnderCrunAsWellAsRunc() throws Exception {
        // The runtime this library names in its own specification, and which nothing exercised:
        // the shim runs runc unless binary_name says otherwise, so a config crun refuses would
        // pass every other test here. It did. crun before 1.14.3 rejects any config declaring OCI
        // 1.2.x, which this library declared without using anything from it.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isExecutable(java.nio.file.Path.of("/usr/bin/crun")),
                "crun is not installed");

        String id = "it-crun-" + java.util.UUID.randomUUID();
        try (var crunClient = io.nanofaas.containerd.spi.ContainerdClient.builder()
                .socketPath(SOCKET).namespace("nanofaas-it")
                .runtimeBinaryName("crun")
                .build()) {
            crunClient.images().pull(ALPINE);
            crunClient.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                    .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done")).build());
            try {
                crunClient.containers().start(id);
                assertThat(crunClient.containers().exec(id, List.of("/bin/sh", "-c", "echo from-crun"))
                        .stdout()).contains("from-crun");
            } finally {
                crunClient.containers().remove(id,
                        RemoveOptions.builder().removeSnapshot(true).force(true).build());
            }
        }
    }
}
