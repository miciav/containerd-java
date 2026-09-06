package io.nanofaas.containerd;

import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading back what a container wrote. Until this existed, a container that died on startup did
 * so silently: the only evidence was a timeout somewhere else, and diagnosing it meant leaving
 * the library behind and re-running the image under ctr.
 */
@Tag("integration")
class ContainerLogsIT extends ContainerdConnectionIT {

    private static final String ALPINE = "docker.io/library/alpine:latest";
    private static Path logs;

    @BeforeAll
    static void ensureImage() throws Exception {
        client.images().pull(ALPINE);
        logs = Files.createTempDirectory("containerd-java-logs-it");
    }

    @Test
    @Timeout(180)
    void capturesBothStreamsInterleaved() throws Exception {
        // Both streams, one file. containerd sends stderr to the same destination as stdout and
        // ignores a second one — asking for two files yields one, with everything in it.
        String id = "it-logs-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .logDirectory(logs)
                .command(List.of("/bin/sh", "-c", "echo to-stdout; echo to-stderr >&2; sleep 30"))
                .build());
        try {
            client.containers().start(id);
            Thread.sleep(2000); // the process writes and the shim flushes on its own schedule

            assertThat(client.containers().logs(id))
                    .contains("to-stdout")
                    .contains("to-stderr");
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }

    @Test
    @Timeout(180)
    void aContainerThatDiesOnStartupSaysWhy() throws Exception {
        // The case that made this necessary: SonarQube died at startup and the only symptom was a
        // ten-minute timeout, because the reason was written to a stream nobody was capturing.
        String id = "it-logs-crash-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .logDirectory(logs)
                .command(List.of("/bin/sh", "-c", "echo 'bootstrap check failed' >&2; exit 78"))
                .build());
        try {
            client.containers().start(id);
            ExitStatus exit = client.containers().wait(id);
            assertThat(exit.code()).isEqualTo(78);

            assertThat(client.containers().logs(id))
                    .as("the reason it died must be readable through the API, not only under ctr")
                    .contains("bootstrap check failed");
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }

    @Test
    @Timeout(180)
    void sayingNothingIsNotAnOptionWhenLoggingWasNeverEnabled() {
        String id = "it-logs-off-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .command(List.of("/bin/sh", "-c", "sleep 5")).build());
        try {
            // Returning empty output here would be indistinguishable from a silent container.
            assertThatThrownBy(() -> client.containers().logs(id))
                    .isInstanceOf(ContainerdException.class)
                    .hasMessageContaining("logDirectory");
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }
}
