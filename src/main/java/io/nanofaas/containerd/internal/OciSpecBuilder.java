package io.nanofaas.containerd.internal;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.nanofaas.containerd.ContainerSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds OCI runtime specs (JSON) wrapped in typeurl {@link Any}s with the type URLs containerd
 * registers for the Go runtime-spec structs. Verified against containerd v2.2.1: the spec travels
 * as JSON bytes, not protobuf.
 */
public final class OciSpecBuilder {

    public static final String SPEC_TYPE_URL = "types.containerd.io/opencontainers/runtime-spec/1/Spec";
    public static final String PROCESS_TYPE_URL = "types.containerd.io/opencontainers/runtime-spec/1/Process";

    static final String SPEC_VERSION = "1.2.0";

    private static final String DEFAULT_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
    private static final List<String> DEFAULT_CAPABILITIES = List.of(
            "CAP_CHOWN", "CAP_DAC_OVERRIDE", "CAP_FSETID", "CAP_FOWNER", "CAP_MKNOD", "CAP_NET_RAW",
            "CAP_SETGID", "CAP_SETUID", "CAP_SETFCAP", "CAP_SETPCAP", "CAP_NET_BIND_SERVICE",
            "CAP_SYS_CHROOT", "CAP_KILL", "CAP_AUDIT_WRITE");
    private static final List<String> DEFAULT_NAMESPACES = List.of("pid", "network", "ipc", "uts", "mount");
    private static final List<String> DEFAULT_MASKED_PATHS = List.of(
            "/proc/acpi", "/proc/asound", "/proc/kcore", "/proc/keys", "/proc/latency_stats",
            "/proc/timer_list", "/proc/timer_stats", "/proc/sched_debug", "/sys/firmware", "/proc/scsi");
    private static final List<String> DEFAULT_READONLY_PATHS = List.of(
            "/proc/bus", "/proc/fs", "/proc/irq", "/proc/sys", "/proc/sysrq-trigger");

    private OciSpecBuilder() {
    }

    /** Builds the full OCI runtime spec for a container as a typeurl Any (JSON payload). */
    public static Any buildContainerSpec(ContainerSpec spec) {
        Struct.Builder root = Struct.newBuilder()
                .putFields("ociVersion", stringValue(SPEC_VERSION))
                .putFields("process", structValue(buildProcess(spec).build()))
                .putFields("root", structValue(Struct.newBuilder()
                        .putFields("path", stringValue("rootfs"))
                        .putFields("readonly", boolValue(spec.readonlyRootfs()))
                        .build()))
                .putFields("hostname", stringValue(spec.hostname() != null ? spec.hostname() : spec.id()))
                .putFields("mounts", buildStandardMounts(spec))
                .putFields("linux", structValue(buildLinux(spec).build()));
        return toAny(SPEC_TYPE_URL, root.build());
    }

    /** Builds the process spec for exec as a typeurl Any (JSON payload). */
    public static Any buildExecSpec(List<String> command, Map<String, String> environment, String workingDir) {
        Struct.Builder process = process(command, environment, workingDir, null, List.of());
        return toAny(PROCESS_TYPE_URL, process.build());
    }

    private static Struct.Builder buildProcess(ContainerSpec spec) {
        Struct.Builder process = process(spec.command(), spec.environment(), spec.workingDir(),
                spec.user(), List.of("TERM=xterm"));
        process.putFields("rlimits", rlimitsValue());
        return process;
    }

    /**
     * The fields every OCI process spec carries, shared by the container's init process and by
     * exec, so the two cannot drift apart. {@code user} is {@code null} for exec (which always
     * runs as uid 0); {@code extraEnv} is prepended after PATH and before the caller's environment.
     */
    private static Struct.Builder process(List<String> command, Map<String, String> environment,
                                          String workingDir, String user, List<String> extraEnv) {
        List<String> args = command == null || command.isEmpty() ? List.of("/bin/sh") : command;
        List<String> env = new ArrayList<>();
        env.add("PATH=" + DEFAULT_PATH);
        env.addAll(extraEnv);
        if (environment != null) {
            environment.forEach((k, v) -> env.add(k + "=" + v));
        }
        return Struct.newBuilder()
                .putFields("terminal", boolValue(false))
                .putFields("user", structValue(parseUser(user)))
                .putFields("args", stringListValue(args))
                .putFields("env", stringListValue(env))
                .putFields("cwd", stringValue(workingDir != null ? workingDir : "/"))
                // Same value for the init process and for exec, matching what docker and ctr do
                // by default: setuid binaries keep working, and a command behaves the same way
                // whether it is the entrypoint or an exec. Previously exec alone set this true,
                // so "sudo" failed under exec and worked as the entrypoint, with nothing saying why.
                .putFields("noNewPrivileges", boolValue(false))
                .putFields("capabilities", structValue(capabilitiesValue()));
    }

    private static Struct parseUser(String user) {
        Struct.Builder b = Struct.newBuilder().putFields("uid", numberValue(0)).putFields("gid", numberValue(0));
        if (user != null && !user.isBlank()) {
            if (user.contains(":")) {
                String[] parts = user.split(":", 2);
                try {
                    b.putFields("uid", numberValue(Long.parseLong(parts[0])));
                    b.putFields("gid", numberValue(Long.parseLong(parts[1])));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("user must be \"uid:gid\" or a username, got: " + user, e);
                }
            }
            // A bare username cannot be honoured: the OCI runtime spec's process.user carries
            // uid/gid only, with no field for a name, so there is nothing to hand the runtime.
            // The process runs as uid 0. Pass "uid:gid" to select a user.
        }
        return b.build();
    }

    private static Value buildStandardMounts(ContainerSpec spec) {
        List<Value> mounts = new ArrayList<>(List.of(
                mount("proc", "proc", "/proc", List.of("nosuid", "noexec", "nodev")),
                mount("tmpfs", "tmpfs", "/dev", List.of("nosuid", "strictatime", "mode=755", "size=65536k")),
                mount("devpts", "devpts", "/dev/pts",
                        List.of("nosuid", "noexec", "newinstance", "ptmxmode=0666", "mode=0620", "gid=5")),
                mount("tmpfs", "shm", "/dev/shm", List.of("nosuid", "noexec", "nodev", "mode=1777", "size=65536k")),
                mount("mqueue", "mqueue", "/dev/mqueue", List.of("nosuid", "noexec", "nodev")),
                mount("sysfs", "sysfs", "/sys", List.of("nosuid", "noexec", "nodev", "ro")),
                mount("cgroup", "cgroup", "/sys/fs/cgroup", List.of("nosuid", "noexec", "nodev", "relatime", "ro"))));
        for (ContainerSpec.MountSpec m : spec.mounts()) {
            mounts.add(mount(m.type(), m.source(), m.destination(), m.options()));
        }
        return listValue(mounts);
    }

    private static Value mount(String type, String source, String destination, List<String> options) {
        Struct.Builder b = Struct.newBuilder()
                .putFields("destination", stringValue(destination))
                .putFields("type", stringValue(type))
                .putFields("source", stringValue(source));
        if (options != null && !options.isEmpty()) {
            b.putFields("options", stringListValue(options));
        }
        return Value.newBuilder().setStructValue(b.build()).build();
    }

    private static Struct.Builder buildLinux(ContainerSpec spec) {
        List<Value> namespaces = new ArrayList<>();
        for (String ns : DEFAULT_NAMESPACES) {
            namespaces.add(Value.newBuilder().setStructValue(Struct.newBuilder()
                    .putFields("type", stringValue(ns))
                    .build()).build());
        }
        Struct.Builder linux = Struct.newBuilder()
                .putFields("namespaces", listValue(namespaces))
                .putFields("maskedPaths", stringListValue(DEFAULT_MASKED_PATHS))
                .putFields("readonlyPaths", stringListValue(DEFAULT_READONLY_PATHS));

        Struct.Builder resources = Struct.newBuilder();
        Struct.Builder cpu = Struct.newBuilder();
        if (spec.cpuShares() > 0) {
            cpu.putFields("shares", numberValue(spec.cpuShares()));
        }
        if (spec.cpuQuotaMicros() > 0) {
            cpu.putFields("quota", numberValue(spec.cpuQuotaMicros()));
        }
        if (spec.cpuPeriodMicros() > 0) {
            cpu.putFields("period", numberValue(spec.cpuPeriodMicros()));
        }
        if (cpu.getFieldsCount() > 0) {
            resources.putFields("cpu", structValue(cpu.build()));
        }
        Struct.Builder memory = Struct.newBuilder();
        if (spec.memoryLimitBytes() > 0) {
            memory.putFields("limit", numberValue(spec.memoryLimitBytes()));
        }
        if (spec.memorySwapLimitBytes() > 0) {
            memory.putFields("swap", numberValue(spec.memorySwapLimitBytes()));
        }
        if (memory.getFieldsCount() > 0) {
            resources.putFields("memory", structValue(memory.build()));
        }
        if (spec.pidsLimit() > 0) {
            resources.putFields("pids", structValue(Struct.newBuilder()
                    .putFields("limit", numberValue(spec.pidsLimit()))
                    .build()));
        }
        if (resources.getFieldsCount() > 0) {
            linux.putFields("resources", structValue(resources.build()));
        }
        return linux;
    }

    private static Struct capabilitiesValue() {
        Struct.Builder caps = Struct.newBuilder();
        for (String set : List.of("bounding", "effective", "inheritable", "permitted", "ambient")) {
            caps.putFields(set, stringListValue(DEFAULT_CAPABILITIES));
        }
        return caps.build();
    }

    private static Value rlimitsValue() {
        // OCI runtime spec: rlimits is an array of {type, hard, soft} objects (specs-go PosixRlimit).
        Value nofile = Value.newBuilder().setStructValue(Struct.newBuilder()
                .putFields("type", stringValue("RLIMIT_NOFILE"))
                .putFields("hard", numberValue(1024))
                .putFields("soft", numberValue(1024))
                .build()).build();
        return listValue(List.of(nofile));
    }

    private static Any toAny(String typeUrl, Struct spec) {
        return Any.newBuilder()
                .setTypeUrl(typeUrl)
                .setValue(ByteString.copyFromUtf8(JsonSupport.print(spec)))
                .build();
    }

    private static Value stringValue(String s) {
        return Value.newBuilder().setStringValue(s).build();
    }

    private static Value numberValue(long n) {
        return Value.newBuilder().setNumberValue(n).build();
    }

    private static Value boolValue(boolean b) {
        return Value.newBuilder().setBoolValue(b).build();
    }

    private static Value stringListValue(List<String> values) {
        List<Value> items = new ArrayList<>(values.size());
        for (String v : values) {
            items.add(stringValue(v));
        }
        return listValue(items);
    }

    private static Value listValue(List<Value> values) {
        return Value.newBuilder().setListValue(ListValue.newBuilder().addAllValues(values)).build();
    }

    private static Value structValue(Struct s) {
        return Value.newBuilder().setStructValue(s).build();
    }
}
