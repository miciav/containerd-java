package io.nanofaas.containerd;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Desired state of a container to create. Build with {@link #builder()}. */
public final class ContainerSpec {

    public record MountSpec(String destination, String type, String source, List<String> options) {
        public MountSpec {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    private final String id;
    private final String image;
    private final List<String> command;
    private final Map<String, String> environment;
    private final String workingDir;
    private final String hostname;
    private final String user;
    private final boolean readonlyRootfs;
    private final List<MountSpec> mounts;
    private final long cpuShares;
    private final long cpuQuotaMicros;
    private final long cpuPeriodMicros;
    private final long memoryLimitBytes;
    private final long memorySwapLimitBytes;
    private final long pidsLimit;
    private final Map<String, String> labels;

    private ContainerSpec(Builder b) {
        this.id = b.id;
        this.image = b.image;
        this.command = List.copyOf(b.command);
        this.environment = Map.copyOf(b.environment);
        this.workingDir = b.workingDir;
        this.hostname = b.hostname;
        this.user = b.user;
        this.readonlyRootfs = b.readonlyRootfs;
        this.mounts = List.copyOf(b.mounts);
        this.cpuShares = b.cpuShares;
        this.cpuQuotaMicros = b.cpuQuotaMicros;
        this.cpuPeriodMicros = b.cpuPeriodMicros;
        this.memoryLimitBytes = b.memoryLimitBytes;
        this.memorySwapLimitBytes = b.memorySwapLimitBytes;
        this.pidsLimit = b.pidsLimit;
        this.labels = Map.copyOf(b.labels);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() { return id; }
    public String image() { return image; }
    public List<String> command() { return command; }
    public Map<String, String> environment() { return environment; }
    public String workingDir() { return workingDir; }
    public String hostname() { return hostname; }
    public String user() { return user; }
    public boolean readonlyRootfs() { return readonlyRootfs; }
    public List<MountSpec> mounts() { return mounts; }
    public long cpuShares() { return cpuShares; }
    public long cpuQuotaMicros() { return cpuQuotaMicros; }
    public long cpuPeriodMicros() { return cpuPeriodMicros; }
    public long memoryLimitBytes() { return memoryLimitBytes; }
    public long memorySwapLimitBytes() { return memorySwapLimitBytes; }
    public long pidsLimit() { return pidsLimit; }
    public Map<String, String> labels() { return labels; }

    public static final class Builder {
        private String id;
        private String image;
        private List<String> command = List.of();
        private Map<String, String> environment = Map.of();
        private String workingDir;
        private String hostname;
        private String user;
        private boolean readonlyRootfs;
        private List<MountSpec> mounts = List.of();
        private long cpuShares;
        private long cpuQuotaMicros;
        private long cpuPeriodMicros;
        private long memoryLimitBytes;
        private long memorySwapLimitBytes;
        private long pidsLimit;
        private Map<String, String> labels = Map.of();

        public Builder id(String id) { this.id = id; return this; }
        public Builder image(String image) { this.image = image; return this; }
        public Builder command(List<String> command) { this.command = Objects.requireNonNull(command, "command"); return this; }
        public Builder environment(Map<String, String> environment) { this.environment = Objects.requireNonNull(environment, "environment"); return this; }
        public Builder workingDir(String workingDir) { this.workingDir = workingDir; return this; }
        public Builder hostname(String hostname) { this.hostname = hostname; return this; }
        public Builder user(String user) { this.user = user; return this; }
        public Builder readonlyRootfs(boolean readonlyRootfs) { this.readonlyRootfs = readonlyRootfs; return this; }
        public Builder mounts(List<MountSpec> mounts) { this.mounts = Objects.requireNonNull(mounts, "mounts"); return this; }
        public Builder cpuShares(long cpuShares) { this.cpuShares = cpuShares; return this; }
        public Builder cpuQuotaMicros(long cpuQuotaMicros) { this.cpuQuotaMicros = cpuQuotaMicros; return this; }
        public Builder cpuPeriodMicros(long cpuPeriodMicros) { this.cpuPeriodMicros = cpuPeriodMicros; return this; }
        public Builder memoryLimitBytes(long memoryLimitBytes) { this.memoryLimitBytes = memoryLimitBytes; return this; }
        public Builder memorySwapLimitBytes(long memorySwapLimitBytes) { this.memorySwapLimitBytes = memorySwapLimitBytes; return this; }
        public Builder pidsLimit(long pidsLimit) { this.pidsLimit = pidsLimit; return this; }
        public Builder labels(Map<String, String> labels) { this.labels = Objects.requireNonNull(labels, "labels"); return this; }

        public ContainerSpec build() {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id is required");
            }
            Identifiers.requireValid(id);
            if (image == null || image.isBlank()) {
                throw new IllegalArgumentException("image is required");
            }
            return new ContainerSpec(this);
        }
    }
}
