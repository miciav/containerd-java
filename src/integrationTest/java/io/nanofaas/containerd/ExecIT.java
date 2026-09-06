package io.nanofaas.containerd;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class ExecIT extends ContainerdConnectionIT {

    private static final String ALPINE = "docker.io/library/alpine:latest";

    @BeforeAll
    static void ensureImage() {
        client.images().pull(ALPINE);
    }

    @Test
    @Timeout(120)
    void execCapturesStdoutStderrAndExitCode() {
        String id = "it-exec-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done")).build());
        try {
            client.containers().start(id);

            ExecResult result = client.containers().exec(id,
                    List.of("/bin/sh", "-c", "echo hello; echo error >&2; exit 3"));
            assertThat(result.exitCode()).isEqualTo(3);
            assertThat(result.stdout()).contains("hello");
            assertThat(result.stderr()).contains("error");
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }

    @Test
    @Timeout(120)
    void execOnStoppedContainerThrows() {
        String id = "it-exec-stopped-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .command(List.of("/bin/sh", "-c", "exit 0")).build());
        try {
            assertThatThrownBy(() -> client.containers().exec(id, List.of("echo", "hi")))
                    .isInstanceOf(ExecException.class);
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }

    @Test
    @Timeout(120)
    void execAndEntrypointSeeTheSameNoNewPrivsFlag() {
        // Reads the flag back from the kernel rather than trusting the spec we sent. The two
        // used to disagree (exec 1, entrypoint 0), so "sudo" failed under exec only.
        String id = "it-nnp-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .command(List.of("/bin/sh", "-c",
                        "grep NoNewPrivs /proc/self/status > /nnp-entrypoint; while true; do sleep 5; done"))
                .build());
        try {
            client.containers().start(id);

            ExecResult fromExec = client.containers().exec(id,
                    List.of("/bin/sh", "-c", "grep NoNewPrivs /proc/self/status"));
            ExecResult fromEntrypoint = client.containers().exec(id,
                    List.of("/bin/sh", "-c", "cat /nnp-entrypoint"));

            assertThat(fromExec.exitCode()).isZero();
            assertThat(fromEntrypoint.exitCode()).isZero();
            assertThat(fromExec.stdout().trim())
                    .as("exec must not be more restricted than the entrypoint")
                    .isEqualTo(fromEntrypoint.stdout().trim())
                    .isEqualTo("NoNewPrivs:\t0");
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }
}
