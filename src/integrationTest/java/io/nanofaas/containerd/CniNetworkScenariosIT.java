package io.nanofaas.containerd;

import io.nanofaas.containerd.cni.CniContainerNetwork;
import io.nanofaas.containerd.spi.ContainerdClient;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a container can actually do on a network, rather than what was configured for it.
 *
 * <p>Three networks, each answering a different question: {@code nanofaas-test} routes out and
 * carries no DNS, {@code dns-test} carries DNS, and {@code nanofaas-isolated} has neither a
 * default route nor a shared bridge. Every assertion is made from inside the container, because
 * the CNI result saying an address was assigned is not the same as the container having one.
 */
@Tag("integration")
class CniNetworkScenariosIT {

    private static final String ALPINE = "docker.io/library/alpine:latest";
    private static final Path PLUGIN_DIR = Path.of("/opt/cni/bin");
    private static final Path CONFIG_DIR = Path.of("/etc/cni/net.d");
    private static final Pattern IPV4 = Pattern.compile("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)");

    private static ContainerdClient client;

    @BeforeAll
    static void connect() {
        String socket = System.getProperty("io.nanofaas.containerd.socket", "/run/containerd/containerd.sock");
        Assumptions.assumeTrue(Files.exists(Path.of(socket)), "containerd socket " + socket + " not present");
        Assumptions.assumeTrue(Files.isDirectory(PLUGIN_DIR), "no CNI plugins at " + PLUGIN_DIR);
        Assumptions.assumeTrue(Files.exists(CONFIG_DIR.resolve("20-dnstest.conflist")),
                "no dns-test configuration in " + CONFIG_DIR);

        client = ContainerdClient.builder()
                .socketPath(socket)
                .namespace("nanofaas-cni-scenarios")
                .network(CniContainerNetwork.builder()
                        .pluginDir(PLUGIN_DIR).configDir(CONFIG_DIR).build())
                .build();
        client.images().pull(ALPINE);
    }

    @AfterAll
    static void disconnect() {
        if (client != null) {
            client.close();
        }
    }

    /** Starts a container on a network and hands it to the body, removing it afterwards. */
    private static void onNetwork(String network, java.util.function.Consumer<String> body) {
        String id = "it-scn-" + UUID.randomUUID();
        client.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                .network(network)
                .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done"))
                .build());
        try {
            client.containers().start(id);
            body.accept(id);
        } finally {
            client.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
        }
    }

    private static ExecResult sh(String id, String command) {
        return client.containers().exec(id, List.of("/bin/sh", "-c", command));
    }

    /** Whether the container can open a TCP connection out. Not ICMP: many hosts drop it. */
    private static boolean reachesInternet(String id) {
        return sh(id, "nc -z -w 5 1.1.1.1 443 >/dev/null 2>&1 && echo OK || echo NO")
                .stdout().trim().equals("OK");
    }

    private static boolean pings(String id, String address) {
        return sh(id, "ping -c 1 -W 3 " + address + " >/dev/null 2>&1 && echo OK || echo NO")
                .stdout().trim().equals("OK");
    }

    private static String addressOf(String id, String ifName) {
        String out = sh(id, "ip -4 addr show " + ifName).stdout();
        var m = IPV4.matcher(out);
        assertThat(m.find()).as("no address on %s: %s", ifName, out).isTrue();
        return m.group(1);
    }

    // --- addressing ------------------------------------------------------------------------

    @Test
    @Timeout(300)
    void theInterfaceIsUpWithAnAddressFromTheNetworksRange() {
        onNetwork("nanofaas-test", id -> {
            assertThat(addressOf(id, "eth0")).startsWith("10.99.");
            assertThat(sh(id, "ip link show eth0").stdout())
                    .as("an address on a down interface carries no traffic").contains("UP");
        });
    }

    @Test
    @Timeout(300)
    void loopbackIsUpToo() {
        // The loopback plugin, which is easy to leave out of a conflist and quietly breaks
        // anything a process tries to reach on 127.0.0.1 inside the container.
        onNetwork("nanofaas-test", id ->
                assertThat(sh(id, "ping -c 1 -W 2 127.0.0.1 >/dev/null 2>&1 && echo UP || echo DOWN")
                        .stdout().trim()).isEqualTo("UP"));
    }

    @Test
    @Timeout(300)
    void eachNetworkAddressesFromItsOwnRange() {
        onNetwork("nanofaas-test", first ->
                onNetwork("dns-test", second -> {
                    assertThat(addressOf(first, "eth0")).startsWith("10.99.");
                    assertThat(addressOf(second, "eth0")).startsWith("10.98.");
                }));
    }

    // --- DNS -------------------------------------------------------------------------------

    @Test
    @Timeout(300)
    void aNetworkCarryingDnsGivesTheContainerAResolvConf() {
        // CNI reports DNS but never applies it; the runtime has to. Before this library did,
        // a container had an address and a route and could not resolve a single name.
        onNetwork("dns-test", id -> {
            String resolvConf = sh(id, "cat /etc/resolv.conf").stdout();
            assertThat(resolvConf)
                    .contains("nameserver 1.1.1.1")
                    .contains("search nanofaas.local");
        });
    }

    @Test
    @Timeout(300)
    void namesResolveThroughThatConfiguration() {
        onNetwork("dns-test", id -> {
            ExecResult lookup = sh(id, "nslookup -timeout=5 example.com 2>&1");
            assertThat(lookup.stdout())
                    .as("a resolv.conf nothing can be resolved through is no better than none")
                    .containsPattern("Address: \\d+\\.\\d+\\.\\d+\\.\\d+");
        });
    }

    @Test
    @Timeout(300)
    void aNetworkWithoutDnsLeavesResolutionAlone() {
        // nanofaas-test declares no DNS, so nothing is written. The container still has a route:
        // reachability by address and by name are separate things, and this is the difference.
        onNetwork("nanofaas-test", id -> {
            assertThat(sh(id, "cat /etc/resolv.conf").stdout().trim()).isEmpty();
            assertThat(reachesInternet(id))
                    .as("reachability by address and by name are separate things")
                    .isTrue();
        });
    }

    // --- routing and isolation --------------------------------------------------------------

    @Test
    @Timeout(300)
    void aNetworkWithADefaultRouteReachesTheInternet() {
        onNetwork("nanofaas-test", id ->
                assertThat(reachesInternet(id))
                        .as("ipMasq plus a default route is what makes egress work")
                        .isTrue());
    }

    @Test
    @Timeout(300)
    void aNetworkWithoutADefaultRouteDoesNot() {
        onNetwork("nanofaas-isolated", id -> {
            assertThat(sh(id, "ip route").stdout()).doesNotContain("default");
            assertThat(reachesInternet(id))
                    .as("no default route means no egress, which is the point of this network")
                    .isFalse();
        });
    }

    @Test
    @Timeout(300)
    void containersOnDifferentNetworksCannotReachEachOther() {
        onNetwork("nanofaas-test", first -> {
            String address = addressOf(first, "eth0");
            onNetwork("nanofaas-isolated", second -> {
                // Paired with a positive control: an environment that dropped all ICMP would
                // satisfy the assertion below for entirely the wrong reason.
                String ownGateway = addressOf(second, "eth0").replaceAll("\\.\\d+$", ".1");
                assertThat(pings(second, ownGateway))
                        .as("ICMP works on this host, so failing to reach %s means something", address)
                        .isTrue();

                assertThat(pings(second, address))
                        .as("separate bridges are separate broadcast domains")
                        .isFalse();
            });
        });
    }

    // --- interface naming ---------------------------------------------------------------------

    @Test
    @Timeout(300)
    void theInterfaceNameIsConfigurable() {
        String id = "it-scn-if-" + UUID.randomUUID();
        try (ContainerdClient named = ContainerdClient.builder()
                .namespace("nanofaas-cni-scenarios")
                .network(CniContainerNetwork.builder()
                        .pluginDir(PLUGIN_DIR).configDir(CONFIG_DIR)
                        .interfaceName("fn0")
                        .build())
                .build()) {
            named.containers().create(ContainerSpec.builder().id(id).image(ALPINE)
                    .network("nanofaas-test")
                    .command(List.of("/bin/sh", "-c", "while true; do sleep 5; done")).build());
            try {
                named.containers().start(id);
                assertThat(named.containers().exec(id, List.of("/bin/sh", "-c", "ip -4 addr show fn0"))
                        .stdout()).contains("fn0").containsPattern("inet 10\\.99\\.");
            } finally {
                named.containers().remove(id, RemoveOptions.builder().removeSnapshot(true).force(true).build());
            }
        }
    }
}
