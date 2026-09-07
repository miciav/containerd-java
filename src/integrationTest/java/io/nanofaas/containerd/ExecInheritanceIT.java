package io.nanofaas.containerd;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an exec'd process inherits from the container it runs in.
 *
 * <p>containerd's own client execs by taking the container's stored process spec and replacing
 * its argv, so everything else — environment, limits — carries over. This library builds the
 * exec spec from scratch instead, which is why each field has to be carried across deliberately
 * and why a test is needed to notice when one stops being.
 */
@Tag("integration")
class ExecInheritanceIT extends ContainerdConnectionIT {

    private static final String ALPINE = "docker.io/library/alpine:latest";
    private static final List<String> SLEEP = List.of("/bin/sh", "-c", "while true; do sleep 5; done");

    @BeforeAll
    static void ensureImage() {
        client.images().pull(ALPINE);
    }

    @Test
    @Timeout(120)
    void execReadsWhatTheCallerSendsOnStdin() {
        String id = "it-exec-stdin-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE).command(SLEEP).build());
        try {
            client.containers().start(id);
            ExecResult result = client.containers().exec(id, ExecSpec.builder()
                    .command(List.of("/bin/sh", "-c", "cat"))
                    .stdin("through stdin\n")
                    .build());
            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout()).contains("through stdin");
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }

    @Test
    @Timeout(120)
    void execRunsUnderTheContainersOwnOpenFilesLimit() {
        // A process that gets a different RLIMIT_NOFILE depending on whether it is the entrypoint
        // or an exec is a trap: the limit the caller asked for silently does not apply to half of
        // what runs in the container.
        String id = "it-exec-rlimit-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .command(SLEEP).openFilesLimit(4096).build());
        try {
            client.containers().start(id);
            ExecResult result = client.containers().exec(id, List.of("/bin/sh", "-c", "ulimit -n"));
            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout().trim())
                    .as("the exec'd process must see the limit the container was created with")
                    .isEqualTo("4096");
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }
}
