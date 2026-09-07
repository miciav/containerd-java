package io.nanofaas.containerd.e2e;

import io.nanofaas.containerd.ContainerSpec;
import io.nanofaas.containerd.ContainerState;
import io.nanofaas.containerd.ContainerStatus;
import io.nanofaas.containerd.Event;
import io.nanofaas.containerd.EventFilter;
import io.nanofaas.containerd.ExecResult;
import io.nanofaas.containerd.ExecSpec;
import io.nanofaas.containerd.RemoveOptions;
import io.nanofaas.containerd.Subscription;
import io.nanofaas.containerd.cni.CniContainerNetwork;
import io.nanofaas.containerd.spi.ContainerdClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives containerd-java and containerd-java-cni through a workload shaped like the one they were
 * written for: pull an image, run a couple of containers on a network, have them talk to each
 * other, read what they printed, and take it all down.
 *
 * <p>Written to be run inside a throwaway VM against a rootless containerd. It reports every
 * scenario and exits non-zero if any failed, so the workflow that started the VM can tell whether
 * the libraries actually work there rather than only that the JVM started.
 */
public final class E2eRun {

    private static final String ALPINE = "docker.io/library/alpine:latest";
    private static final Pattern IPV4 = Pattern.compile("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)");

    private final ContainerdClient client;
    private final String network;
    private final Path logs;
    private final List<Scenario> scenarios = new ArrayList<>();
    private final List<String> created = new ArrayList<>();

    private E2eRun(ContainerdClient client, String network, Path logs) {
        this.client = client;
        this.network = network;
        this.logs = logs;
    }

    public static void main(String[] args) throws Exception {
        String socket = System.getProperty("io.nanofaas.containerd.socket",
                System.getenv().getOrDefault("CONTAINERD_SOCKET", "/run/containerd/containerd.sock"));
        String network = System.getenv().getOrDefault("E2E_NETWORK", "e2e-net");
        Path cniConfig = Path.of(System.getenv().getOrDefault("E2E_CNI_CONFIG",
                System.getProperty("user.home") + "/.config/cni/net.d"));
        Path cniPlugins = Path.of(System.getenv().getOrDefault("E2E_CNI_PLUGINS", "/usr/local/libexec/cni"));
        Path state = Files.createDirectories(Path.of(System.getProperty("user.home"), ".local/share/containerd-java"));
        Path logs = Files.createDirectories(state.resolve("logs"));

        System.out.println("containerd socket : " + socket);
        System.out.println("CNI plugins       : " + cniPlugins);
        System.out.println("CNI configuration : " + cniConfig);
        System.out.println("network           : " + network);
        System.out.println();

        try (ContainerdClient client = ContainerdClient.builder()
                .socketPath(socket)
                .namespace("containerd-java-e2e")
                .runtimeBinaryName("crun")
                .stateDirectory(state)
                .network(CniContainerNetwork.builder()
                        .pluginDir(cniPlugins).configDir(cniConfig).build())
                .build()) {
            System.exit(new E2eRun(client, network, logs).run());
        }
    }

    /** No scenario may take longer than this; one that does is reported, not waited on. */
    private static final java.time.Duration SCENARIO_LIMIT = java.time.Duration.ofMinutes(3);

    /**
     * Runs one scenario under a time limit.
     *
     * <p>A hung scenario would otherwise take the whole run with it and say nothing about which
     * one it was — the failure mode this run exists to detect is exactly the kind that hangs.
     */
    private void guarded(String name, Runnable body) {
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "scenario-" + name);
            t.setDaemon(true);
            return t;
        });
        try {
            executor.submit(body).get(SCENARIO_LIMIT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            Scenario timedOut = new Scenario(name + " (timed out)");
            timedOut.failed("still running after " + SCENARIO_LIMIT + "; something is blocked");
            finish(timedOut);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            Scenario threw = new Scenario(name + " (threw)");
            threw.failed(String.valueOf(e.getCause()));
            finish(threw);
        } finally {
            executor.shutdownNow();
        }
    }

    private int run() {
        try {
            guarded("connectivity", this::connectivity);
            guarded("images", this::imageHandling);
            var backendHolder = new java.util.concurrent.atomic.AtomicReference<String>();
            guarded("network", () -> backendHolder.set(networkedPair()));
            String backend = backendHolder.get();
            guarded("logs", this::containerOutput);
            guarded("exec", this::executionInsideAContainer);
            guarded("limits", this::resourceLimitsAndImageDefaults);
            guarded("events", this::eventsWhileAContainerRuns);
            guarded("host network", this::hostNetworking);
            if (backend != null) {
                remove(backend);
            }
            guarded("teardown", this::teardownIsComplete);
        } catch (RuntimeException e) {
            Scenario crashed = new Scenario("the run itself");
            crashed.failed("unexpected " + e);
            finish(crashed);
        } finally {
            for (String id : new ArrayList<>(created)) {
                remove(id);
            }
        }
        return report();
    }

    // --- scenarios ---------------------------------------------------------------------------

    private void connectivity() {
        Scenario s = new Scenario("talks to containerd");
        try {
            var version = client.version();
            s.note("containerd " + version.version() + " (" + version.revision() + ")");
            s.require(!version.version().isBlank(), "no version reported");
            s.note("namespace " + client.namespace() + ", snapshotter " + client.snapshotter());
        } catch (RuntimeException e) {
            s.failed(e.toString());
        }
        finish(s);
    }

    private void imageHandling() {
        Scenario s = new Scenario("pulls and inspects an image");
        try {
            client.images().pull(ALPINE);
            var image = client.images().get(ALPINE);
            s.note(image.name() + " " + image.digest().substring(0, 19) + "… " + image.size() + " bytes");
            s.require(image.digest().startsWith("sha256:"), "digest looks wrong: " + image.digest());
            s.require(client.images().list().stream().anyMatch(i -> i.name().equals(ALPINE)),
                    "the image is not in the list after pulling it");
        } catch (RuntimeException e) {
            s.failed(e.toString());
        }
        finish(s);
    }

    /** Two containers on one network: the shape of a function talking to a service. */
    private String networkedPair() {
        Scenario s = new Scenario("two containers share a network and reach each other");
        String backend = id("backend");
        String frontend = id("frontend");
        try {
            create(backend, spec(backend).command(sleepForever()).network(network).build());
            client.containers().start(backend);
            String backendAddress = addressOf(backend);
            s.note("backend at " + backendAddress);
            s.require(backendAddress != null, "the backend got no address");

            create(frontend, spec(frontend).command(sleepForever()).network(network).build());
            client.containers().start(frontend);
            String frontendAddress = addressOf(frontend);
            s.note("frontend at " + frontendAddress);
            s.require(!java.util.Objects.equals(backendAddress, frontendAddress),
                    "both containers were given the same address");

            if (backendAddress != null) {
                ExecResult reach = sh(frontend, "ping -c 2 -W 3 " + backendAddress
                        + " >/dev/null 2>&1 && echo REACHED || echo NO");
                s.note("frontend -> backend: " + reach.stdout().trim());
                s.require(reach.stdout().contains("REACHED"),
                        "the frontend could not reach the backend across the network");
            }

            String resolvConf = sh(backend, "cat /etc/resolv.conf 2>/dev/null || true").stdout().trim();
            s.note("resolv.conf: " + (resolvConf.isEmpty() ? "(empty)" : resolvConf.replace("\n", " / ")));
            if (!resolvConf.isEmpty()) {
                ExecResult lookup = sh(backend, "nslookup -timeout=5 example.com 2>&1 | tail -3");
                boolean resolved = lookup.stdout().contains("Address");
                s.note("name resolution: " + (resolved ? "works" : "failed"));
                s.require(resolved, "a resolv.conf was written but nothing resolves through it");
            }
            remove(frontend);
            return backend;
        } catch (RuntimeException e) {
            s.failed(e.toString());
            return null;
        } finally {
            finish(s);
        }
    }

    private void containerOutput() {
        Scenario s = new Scenario("captures what a container printed");
        String id = id("logger");
        try {
            create(id, spec(id).logDirectory(logs)
                    .command(List.of("/bin/sh", "-c",
                            "echo to-stdout; echo to-stderr >&2; sleep 30")).build());
            client.containers().start(id);
            Thread.sleep(2000);
            String output = client.containers().logs(id);
            s.note("logged: " + output.trim().replace("\n", " / "));
            s.require(output.contains("to-stdout"), "stdout is missing from the log");
            s.require(output.contains("to-stderr"), "stderr is missing from the log");
        } catch (RuntimeException | InterruptedException e) {
            s.failed(e.toString());
        } finally {
            remove(id);
            finish(s);
        }
    }

    private void executionInsideAContainer() {
        Scenario s = new Scenario("runs commands inside a running container");
        String id = id("exec");
        try {
            create(id, spec(id).environment(Map.of("GREETING", "ciao")).command(sleepForever()).build());
            client.containers().start(id);

            ExecResult exit = sh(id, "echo out; echo err >&2; exit 7");
            s.note("exit=" + exit.exitCode() + " stdout=" + exit.stdout().trim()
                    + " stderr=" + exit.stderr().trim());
            s.require(exit.exitCode() == 7, "the exit code did not come back");
            s.require(exit.stdout().contains("out") && exit.stderr().contains("err"),
                    "the streams were not captured separately");

            ExecResult inherited = sh(id, "echo $GREETING-$PATH");
            s.note("inherited environment: " + inherited.stdout().trim());
            s.require(inherited.stdout().contains("ciao"),
                    "the exec did not inherit the container's environment");

            ExecResult withStdin = client.containers().exec(id, ExecSpec.builder()
                    .command(List.of("/bin/sh", "-c", "cat"))
                    .stdin("through stdin\n").build());
            s.note("stdin: " + withStdin.stdout().trim());
            s.require(withStdin.stdout().contains("through stdin"), "stdin never reached the process");
        } catch (RuntimeException e) {
            s.failed(e.toString());
        } finally {
            remove(id);
            finish(s);
        }
    }

    private void resourceLimitsAndImageDefaults() {
        Scenario s = new Scenario("applies limits and the image's own configuration");
        String id = id("limits");
        try {
            // No command: what runs comes from the image. Alpine's is /bin/sh, which exits at once
            // without a terminal, so the container is expected to be short-lived here.
            create(id, spec(id)
                    .openFilesLimit(4096)
                    .memoryLimitBytes(256L * 1024 * 1024)
                    .pidsLimit(64)
                    .command(sleepForever())
                    .build());
            client.containers().start(id);

            ExecResult limits = sh(id, "ulimit -n");
            s.note("open files limit inside: " + limits.stdout().trim());
            s.require(limits.stdout().trim().equals("4096"),
                    "RLIMIT_NOFILE did not reach the container");

            ContainerStatus status = client.containers().inspect(id);
            s.note("state=" + status.state() + " pid=" + status.pid());
            s.require(status.state() == ContainerState.RUNNING, "the container is not running");
            s.require(status.pid() > 0, "no pid reported");
        } catch (RuntimeException e) {
            s.failed(e.toString());
        } finally {
            remove(id);
            finish(s);
        }
    }

    private void eventsWhileAContainerRuns() {
        Scenario s = new Scenario("reports containerd events as they happen");
        String id = id("events");
        var seen = new ConcurrentLinkedQueue<String>();
        Subscription subscription = null;
        try {
            subscription = client.events().subscribe(
                    EventFilter.topics("/tasks/start", "/tasks/exit", "/tasks/delete"),
                    (Event e) -> seen.add(e.topic()));
            Thread.sleep(500);

            create(id, spec(id).command(List.of("/bin/sh", "-c", "exit 0")).build());
            client.containers().start(id);
            client.containers().wait(id);
            Thread.sleep(2000);

            s.note("topics seen: " + String.join(", ", seen));
            s.require(seen.stream().anyMatch(t -> t.equals("/tasks/start")),
                    "no start event arrived");
            s.require(seen.stream().anyMatch(t -> t.equals("/tasks/exit")),
                    "no exit event arrived");
        } catch (RuntimeException | InterruptedException e) {
            s.failed(e.toString());
        } finally {
            if (subscription != null) {
                subscription.close();
            }
            remove(id);
            finish(s);
        }
    }

    private void hostNetworking() {
        Scenario s = new Scenario("shares the host's network when asked");
        String id = id("hostnet");
        try {
            create(id, spec(id).hostNetwork(true).command(sleepForever()).build());
            client.containers().start(id);
            String interfaces = sh(id, "ip -o link show | wc -l").stdout().trim();
            s.note("interfaces visible: " + interfaces);
            s.require(Integer.parseInt(interfaces) > 1,
                    "only loopback is visible, so this is not the host's network");
        } catch (RuntimeException e) {
            s.failed(e.toString());
        } finally {
            remove(id);
            finish(s);
        }
    }

    private void teardownIsComplete() {
        Scenario s = new Scenario("leaves nothing behind");
        try {
            var remaining = client.containers().list();
            s.note("containers still present: " + remaining.size());
            s.require(remaining.isEmpty(), "containers were left behind: " + remaining);
        } catch (RuntimeException e) {
            s.failed(e.toString());
        }
        finish(s);
    }

    // --- helpers -----------------------------------------------------------------------------

    private static List<String> sleepForever() {
        return List.of("/bin/sh", "-c", "while true; do sleep 5; done");
    }

    private ContainerSpec.Builder spec(String id) {
        return ContainerSpec.builder().id(id).image(ALPINE);
    }

    private String id(String role) {
        return "e2e-" + role + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void create(String id, ContainerSpec spec) {
        client.containers().create(spec);
        created.add(id);
    }

    private void remove(String id) {
        created.remove(id);
        try {
            client.containers().remove(id, RemoveOptions.builder()
                    .removeSnapshot(true).force(true).build());
        } catch (RuntimeException e) {
            System.out.println("  (could not remove " + id + ": " + e.getMessage() + ")");
        }
    }

    private ExecResult sh(String id, String command) {
        return client.containers().exec(id, List.of("/bin/sh", "-c", command));
    }

    private String addressOf(String id) {
        Matcher m = IPV4.matcher(sh(id, "ip -4 addr show eth0").stdout());
        return m.find() ? m.group(1) : null;
    }

    /**
     * Records a scenario and prints it at once, rather than collecting them for the end. A long
     * run that hangs otherwise says nothing about where it got to, which is exactly when its
     * output matters most.
     */
    private void finish(Scenario s) {
        scenarios.add(s);
        System.out.print(s.render());
        System.out.flush();
    }

    private int report() {
        System.out.println();
        long failed = scenarios.stream().filter(s -> !s.passed()).count();
        System.out.println();
        System.out.printf("%d scenarios, %d failed%n", scenarios.size(), failed);
        return failed == 0 ? 0 : 1;
    }
}
