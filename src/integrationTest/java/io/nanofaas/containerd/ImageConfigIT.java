package io.nanofaas.containerd;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The image's own configuration, against a real image that relies on it. postgres ships an
 * entrypoint, a PATH holding its binaries and a PGDATA — a container built from it is unusable
 * unless all three reach the runtime.
 */
@Tag("integration")
class ImageConfigIT extends ContainerdConnectionIT {

    private static final String POSTGRES = "docker.io/library/postgres:16-alpine";

    @BeforeAll
    static void ensureImage() {
        client.images().pull(POSTGRES);
    }

    @Test
    @Timeout(300)
    void theImagesEnvironmentAndPathReachTheProcess() {
        String id = "it-imgenv-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(POSTGRES)
                .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done")).build());
        try {
            client.containers().start(id);

            ExecResult env = client.containers().exec(id, List.of("/bin/sh", "-c", "echo $PGDATA"));
            ExecResult path = client.containers().exec(id, List.of("/bin/sh", "-c", "command -v postgres"));

            assertThat(env.stdout().trim())
                    .as("PGDATA comes from the image, nowhere else").isEqualTo("/var/lib/postgresql/data");
            assertThat(path.stdout().trim())
                    .as("the image's PATH must win over this library's default").endsWith("/postgres");
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }

    @Test
    @Timeout(300)
    void theImagesEntrypointRunsWhenNoCommandIsGiven() throws Exception {
        // No command at all: everything that makes this container a database comes from the image.
        String id = "it-imgentry-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(POSTGRES)
                .environment(Map.of("POSTGRES_PASSWORD", "test", "POSTGRES_DB", "probe"))
                .build());
        try {
            client.containers().start(id);

            String ready = null;
            for (int attempt = 0; attempt < 60; attempt++) {
                ExecResult result = client.containers().exec(id,
                        List.of("/bin/sh", "-c", "pg_isready -U postgres 2>&1 || true"));
                ready = result.stdout().trim();
                if (ready.contains("accepting connections")) {
                    break;
                }
                Thread.sleep(1000);
            }

            assertThat(ready)
                    .as("the image's entrypoint initialised and started postgres with no command from us")
                    .contains("accepting connections");
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }
}
