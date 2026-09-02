package io.nanofaas.containerd;

import io.nanofaas.containerd.spi.ContainerdClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
public class ContainerdConnectionIT {

    static final String SOCKET = System.getProperty("io.nanofaas.containerd.socket", "/run/containerd/containerd.sock");

    static ContainerdClient client;

    @BeforeAll
    static void connect() {
        Assumptions.assumeTrue(Files.exists(Path.of(SOCKET)), "containerd socket " + SOCKET + " not present");
        Assumptions.assumeTrue(Files.isWritable(Path.of(SOCKET)) || Files.isReadable(Path.of(SOCKET)),
                "socket " + SOCKET + " not accessible by current user (try running as root)");
        client = ContainerdClient.builder().socketPath(SOCKET).namespace("nanofaas-it").build();
    }

    @AfterAll
    static void disconnect() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void reportsVersion() {
        Version version = client.version();
        assertThat(version.version()).isNotBlank();
        assertThat(version.revision()).isNotBlank();
    }

    @Test
    void namespaceIsConfigurable() {
        assertThat(client.namespace()).isEqualTo("nanofaas-it");
    }
}
