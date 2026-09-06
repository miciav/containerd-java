package io.nanofaas.containerd;

import io.nanofaas.containerd.cni.CniContainerNetwork;
import io.nanofaas.containerd.spi.ContainerdClient;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A container on a real CNI network. Everything below the API is real: the bridge plugin, an IPAM
 * pool, a veth pair and a namespace that exists only while the task does.
 *
 * <p>Skipped rather than failed where CNI is not installed — the plugins are a separate download,
 * and this suite already runs on machines that have containerd but no networking set up.
 */
@Tag("integration")
class CniNetworkIT {

    private static final String ALPINE = "docker.io/library/alpine:latest";
    private static final String NETWORK = "nanofaas-test";
    private static final Path PLUGIN_DIR = Path.of("/opt/cni/bin");
    private static final Path CONFIG_DIR = Path.of("/etc/cni/net.d");

    private static ContainerdClient client;

    @BeforeAll
    static void connect() {
        String socket = System.getProperty("io.nanofaas.containerd.socket", "/run/containerd/containerd.sock");
        Assumptions.assumeTrue(Files.exists(Path.of(socket)), "containerd socket " + socket + " not present");
        Assumptions.assumeTrue(Files.isDirectory(PLUGIN_DIR), "no CNI plugins at " + PLUGIN_DIR);
        Assumptions.assumeTrue(Files.exists(CONFIG_DIR.resolve("10-nanofaas.conflist")),
                "no " + NETWORK + " configuration in " + CONFIG_DIR);

        client = ContainerdClient.builder()
                .socketPath(socket)
                .namespace("nanofaas-cni-it")
                .network(CniContainerNetwork.builder()
                        .pluginDir(PLUGIN_DIR)
                        .configDir(CONFIG_DIR)
                        .build())
                .build();
        client.images().pull(ALPINE);
    }

    @AfterAll
    static void disconnect() {
        if (client != null) {
            client.close();
        }
    }

    private static String addressesIn(String id) {
        return client.containers().exec(id, List.of("/bin/sh", "-c", "ip -4 addr show")).stdout();
    }

    @Test
    @Timeout(300)
    void aContainerOnTheNetworkGetsAnAddressAndARoute() {
        String id = "it-cni-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .network(NETWORK)
                .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done"))
                .build());
        try {
            client.containers().start(id);

            assertThat(addressesIn(id))
                    .as("the bridge plugin puts an address from the pool on eth0")
                    .contains("eth0")
                    .containsPattern("inet 10\\.99\\.\\d+\\.\\d+");

            String routes = client.containers()
                    .exec(id, List.of("/bin/sh", "-c", "ip route")).stdout();
            assertThat(routes)
                    .as("and a default route through the gateway, or nothing outside can be reached")
                    .contains("default via 10.99.0.1");
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }

    @Test
    @Timeout(300)
    void theContainerCanReachTheHostAcrossTheBridge() {
        String id = "it-cni-reach-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .network(NETWORK)
                .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done"))
                .build());
        try {
            client.containers().start(id);

            // The gateway is the host's end of the veth pair. Reaching it proves the pair is up
            // and routed, not merely that an address was written into the namespace.
            ExecResult ping = client.containers()
                    .exec(id, List.of("/bin/sh", "-c", "ping -c 2 -W 3 10.99.0.1"));

            assertThat(ping.exitCode()).as("stdout was: %s", ping.stdout()).isZero();
            assertThat(ping.stdout()).contains("2 packets received");
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }

    @Test
    @Timeout(300)
    void twoContainersOnTheSameNetworkGetDifferentAddressesAndSeeEachOther() {
        String first = "it-cni-a-" + UUID.randomUUID();
        String second = "it-cni-b-" + UUID.randomUUID();
        var run = ContainerSpec.builder().image(ALPINE).network(NETWORK)
                .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done"));
        client.containers().create(run.id(first).build());
        client.containers().create(run.id(second).build());
        try {
            client.containers().start(first);
            client.containers().start(second);

            String firstAddress = ipOf(first);
            String secondAddress = ipOf(second);
            assertThat(firstAddress)
                    .as("IPAM must not hand the same address to two live containers")
                    .isNotEqualTo(secondAddress);

            ExecResult ping = client.containers()
                    .exec(second, List.of("/bin/sh", "-c", "ping -c 2 -W 3 " + firstAddress));
            assertThat(ping.exitCode()).as("stdout was: %s", ping.stdout()).isZero();
        } finally {
            for (String id : List.of(first, second)) {
                client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
            }
        }
    }

    @Test
    @Timeout(300)
    void removingAContainerReleasesItsAddress() {
        String id = "it-cni-release-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE).network(NETWORK)
                .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done")).build());
        client.containers().start(id);
        String address = ipOf(id);
        Path allocation = Path.of("/var/lib/cni/networks", NETWORK, address);
        assertThat(allocation).as("host-local records the holder of each address").exists();

        client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());

        // The allocation file, not the next container's address: host-local hands out addresses
        // sequentially from the last one reserved rather than reusing the lowest free one, so a
        // released address does not come back until the range wraps. Whether the file is gone is
        // the actual question — if detach had not run, or had run after the namespace went away,
        // it would still be there naming a container that no longer exists.
        assertThat(allocation)
                .as("the address must be released back to the pool when the container goes")
                .doesNotExist();
    }

    private static String ipOf(String id) {
        String out = addressesIn(id);
        var matcher = java.util.regex.Pattern.compile("inet (10\\.99\\.\\d+\\.\\d+)").matcher(out);
        assertThat(matcher.find()).as("no address found in: %s", out).isTrue();
        return matcher.group(1);
    }
}
