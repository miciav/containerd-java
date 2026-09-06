package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.nanofaas.containerd.*;
import io.nanofaas.containerd.spi.Containers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Container lifecycle on top of containerd's Containers, Tasks and Snapshots services. */
public final class ContainersServiceImpl implements Containers {

    private static final Logger log = LoggerFactory.getLogger(ContainersServiceImpl.class);

    /**
     * Where a container's captured output lives, kept as a label on the container itself. The path
     * is decided at create time but needed again at task create and at every logs() call, and
     * containerd is already storing metadata for this container — holding it here means the caller
     * never has to hand it back, and it survives a client restart.
     */
    static final String LOG_LABEL = "io.nanofaas.containerd/log.path";

    /**
     * The network a container was attached to, kept on the container itself. Detaching happens
     * long after attaching, sometimes from a different process entirely, and asking the caller to
     * remember would mean a crash in between leaks an address nobody can account for.
     */
    static final String NETWORK_LABEL = "io.nanofaas.containerd/cni.network";

    /** Default grace period between SIGTERM and SIGKILL in {@link #stop(String)}. */
    static final java.time.Duration DEFAULT_STOP_TIMEOUT = java.time.Duration.ofSeconds(10);

    /** How long {@link #close()} waits for in-flight exec IO before abandoning it. */
    static final java.time.Duration IO_SHUTDOWN_TIMEOUT = java.time.Duration.ofSeconds(5);

    private final containerd.services.containers.v1.ContainersGrpc.ContainersBlockingStub stub;
    private final SnapshotManager snapshots;
    private final LeaseManager leases;
    private final ImageRootfsResolver rootfsResolver;
    private final TasksServiceImpl tasks;
    private final String snapshotter;
    private final String runtimeName;
    private final java.time.Duration stopTimeout;
    private final io.nanofaas.containerd.spi.ContainerNetwork network;
    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ContainersServiceImpl(ManagedChannel channel, String snapshotter, String runtimeName, String runtimeBinaryName) {
        this(channel, snapshotter, runtimeName, runtimeBinaryName, DEFAULT_STOP_TIMEOUT, null);
    }

    public ContainersServiceImpl(ManagedChannel channel, String snapshotter, String runtimeName,
                                 String runtimeBinaryName, java.time.Duration stopTimeout) {
        this(channel, snapshotter, runtimeName, runtimeBinaryName, stopTimeout, null);
    }

    public ContainersServiceImpl(ManagedChannel channel, String snapshotter, String runtimeName,
                                 String runtimeBinaryName, java.time.Duration stopTimeout,
                                 io.nanofaas.containerd.spi.ContainerNetwork network) {
        this.network = network;
        this.stub = containerd.services.containers.v1.ContainersGrpc.newBlockingStub(channel);
        this.snapshots = new SnapshotManager(channel);
        this.leases = new LeaseManager(channel);
        this.rootfsResolver = new ImageRootfsResolver(channel);
        this.tasks = new TasksServiceImpl(channel, runtimeBinaryName);
        this.snapshotter = snapshotter;
        this.runtimeName = runtimeName;
        this.stopTimeout = stopTimeout;
    }

    @Override
    public Container create(ContainerSpec spec) {
        ProtoMapper.requireValidId(spec.id());
        if (spec.network() != null && network == null) {
            throw new ContainerdException("container " + spec.id() + " asks for network \""
                    + spec.network() + "\" but this client has no ContainerNetwork. Build it with"
                    + " .network(...) — starting the container without one would leave it silently"
                    + " unreachable");
        }
        log.debug("container create start: id={} image={}", spec.id(), spec.image());

        ImageRootfsResolver.ResolvedImage image;
        try {
            image = rootfsResolver.resolve(spec.image());
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.IMAGE);
        }
        // The snapshot is prepared before the container that will own it exists. A lease owns it
        // in between, so a process killed in that window leaves something containerd will collect
        // when the lease expires rather than a snapshot nobody can account for.
        LeaseManager.Lease lease = leases.create("containerd-java-create-" + spec.id());
        try {
            prepareSnapshotOrThrow(spec.id(), image.chainId(), lease);
            return createContainer(spec, image.config(), lease);
        } finally {
            // Released as soon as the container references the snapshot itself; on the failure
            // path the snapshot is already gone and the lease has nothing left to hold.
            leases.release(lease);
        }
    }

    private Container createContainer(ContainerSpec spec, ImageConfig imageConfig,
                                      LeaseManager.Lease lease) {
        try {
            var container = containerd.services.containers.v1.Container.newBuilder()
                    .setId(spec.id())
                    .setImage(spec.image())
                    .setSnapshotter(snapshotter)
                    .setSnapshotKey(spec.id())
                    .setSpec(OciSpecBuilder.buildContainerSpec(spec, imageConfig))
                    .setRuntime(containerd.services.containers.v1.Container.Runtime.newBuilder()
                            .setName(runtimeName))
                    // User labels first: the GC ref must win, because losing it would let
                    // containerd collect the snapshot out from under a live container.
                    .putAllLabels(spec.labels())
                    .putAllLabels(logLabels(spec))
                    .putAllLabels(networkLabels(spec))
                    .putLabels("containerd.io/gc.ref.snapshot." + snapshotter, spec.id())
                    .build();
            var containers = lease == null ? stub : stub.withInterceptors(lease.asHeader());
            var created = containers.create(containerd.services.containers.v1.CreateContainerRequest.newBuilder()
                    .setContainer(container).build()).getContainer();
            log.debug("container create complete: id={}", spec.id());
            return ProtoMapper.map(created);
        } catch (RuntimeException e) {
            // Every failure after prepare, not just a gRPC one. The snapshot exists and nothing
            // references it yet, so anything that stops the container from being created leaves it
            // owned by no one and never collected — the stale snapshot that prepareSnapshotOrThrow
            // has to work around above. Catching only StatusRuntimeException left that hole open
            // for every other failure: a spec this code cannot build, a log directory it cannot
            // create, anything thrown between the two calls.
            try {
                snapshots.remove(snapshotter, spec.id());
            } catch (RuntimeException cleanupFailure) {
                log.warn("failed to clean up snapshot {} after container create failure", spec.id(), cleanupFailure);
            }
            if (e instanceof StatusRuntimeException status) {
                if (status.getStatus().getCode() == io.grpc.Status.Code.ALREADY_EXISTS) {
                    throw new ContainerAlreadyExistsException("container " + spec.id() + " already exists", status);
                }
                throw StatusExceptionMapper.map(status, StatusExceptionMapper.ResourceKind.CONTAINER);
            }
            throw e;
        }
    }

    /**
     * The log-path labels for a spec that asks for captured output, empty otherwise. Creating the
     * directory here rather than at task start means a failure to do so is reported by create(),
     * where the caller asked for it.
     */
    private static java.util.Map<String, String> logLabels(ContainerSpec spec) {
        if (spec.logDirectory() == null) {
            return java.util.Map.of();
        }
        try {
            Files.createDirectories(spec.logDirectory());
        } catch (IOException e) {
            throw new ContainerdException("could not create the log directory " + spec.logDirectory(), e);
        }
        return java.util.Map.of(LOG_LABEL, spec.logDirectory().resolve(spec.id() + ".log").toString());
    }

    /** The network label, so detach can find the network long after create decided it. */
    private static java.util.Map<String, String> networkLabels(ContainerSpec spec) {
        return spec.network() == null ? java.util.Map.of() : java.util.Map.of(NETWORK_LABEL, spec.network());
    }

    /**
     * Prepares the container's snapshot. ALREADY_EXISTS means the id is already taken: if the
     * container exists this is a duplicate create; if it does not, a stale snapshot from a
     * previous partial create is removed and prepare is retried once.
     */
    private void prepareSnapshotOrThrow(String id, String parentChainId, LeaseManager.Lease lease) {
        io.grpc.ClientInterceptor header = lease == null ? null : lease.asHeader();
        try {
            snapshots.prepare(snapshotter, id, parentChainId, header);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != io.grpc.Status.Code.ALREADY_EXISTS) {
                throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.SNAPSHOT);
            }
            if (containerExists(id)) {
                throw new ContainerAlreadyExistsException("container " + id + " already exists", e);
            }
            log.warn("removing stale snapshot {} (no container with that id) and retrying prepare", id);
            try {
                snapshots.remove(snapshotter, id);
                snapshots.prepare(snapshotter, id, parentChainId, header);
            } catch (StatusRuntimeException retryFailure) {
                throw StatusExceptionMapper.map(retryFailure, StatusExceptionMapper.ResourceKind.SNAPSHOT);
            }
        }
    }

    private boolean containerExists(String id) {
        try {
            stub.get(containerd.services.containers.v1.GetContainerRequest.newBuilder()
                    .setId(id).build());
            return true;
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                return false;
            }
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.CONTAINER);
        }
    }

    @Override
    public ContainerStatus inspect(String id) {
        containerd.services.containers.v1.Container container;
        try {
            container = stub.get(containerd.services.containers.v1.GetContainerRequest.newBuilder()
                    .setId(id).build()).getContainer();
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.CONTAINER);
        }
        ContainerState state = ContainerState.UNKNOWN;
        int pid = -1;
        ExitStatus exitStatus = null;
        var task = tasks.find(id);
        if (task.isPresent()) {
            state = task.get().state();
            pid = task.get().pid();
            if (state == ContainerState.STOPPED) {
                exitStatus = new ExitStatus(task.get().exitCode(), task.get().exitedAt());
            }
        }
        return new ContainerStatus(container.getId(), container.getImage(), state, pid,
                exitStatus, container.getSnapshotKey(), ProtoMapper.map(container).createdAt());
    }

    @Override
    public String logs(String id) {
        String path = containerOf(id).getLabelsMap().get(LOG_LABEL);
        if (path == null) {
            throw new ContainerdException("container " + id + " was not created with a log directory,"
                    + " so containerd discarded its output. Set ContainerSpec.logDirectory(...) before"
                    + " creating it: a task's destination cannot be chosen after it has started");
        }
        return readLog(Path.of(path));
    }

    /** Reads a log file, treating "not there yet" as empty: the shim creates it when it first writes. */
    private static String readLog(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new ContainerdException("could not read the container log " + path, e);
        }
    }

    private containerd.services.containers.v1.Container containerOf(String id) {
        try {
            return stub.get(containerd.services.containers.v1.GetContainerRequest.newBuilder()
                    .setId(id).build()).getContainer();
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.CONTAINER);
        }
    }

    @Override
    public List<Container> list() {
        try {
            return stub.list(containerd.services.containers.v1.ListContainersRequest.getDefaultInstance())
                    .getContainersList().stream()
                    .map(ProtoMapper::map)
                    .toList();
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.CONTAINER);
        }
    }

    @Override
    public void remove(String id, RemoveOptions options) {
        log.debug("container remove: id={} removeSnapshot={} force={}",
                id, options.removeSnapshot(), options.force());
        var existingTask = tasks.find(id);
        if (existingTask.isPresent()) {
            var task = existingTask.get();
            boolean running = task.state() == ContainerState.RUNNING
                    || task.state() == ContainerState.CREATED
                    || task.state() == ContainerState.STARTING
                    || task.state() == ContainerState.PAUSED;
            if (running && !options.force()) {
                throw new ContainerdException(
                        "container " + id + " is still running; stop it first or use RemoveOptions.force(true)");
            }
            if (running) {
                stop(id);
            } else {
                // Already exited, so its namespace is gone; the address it held is not, and only
                // the container id identifies it now.
                detachNetwork(id, -1);
                tasks.delete(id);
            }
        }

        containerd.services.containers.v1.Container container;
        try {
            container = stub.get(containerd.services.containers.v1.GetContainerRequest.newBuilder()
                    .setId(id).build()).getContainer();
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                log.debug("container {} already gone (idempotent remove)", id);
                return;
            }
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.CONTAINER);
        }

        try {
            stub.delete(containerd.services.containers.v1.DeleteContainerRequest.newBuilder()
                    .setId(id).build());
            if (options.removeSnapshot() && !container.getSnapshotKey().isEmpty()) {
                snapshots.remove(container.getSnapshotter(), container.getSnapshotKey()); // idempotent
            }
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.CONTAINER);
        }
        log.debug("container remove complete: id={}", id);
    }

    @Override
    public int start(String id) {
        TaskInfo existing = tasks.find(id).orElse(null);
        if (existing != null && (existing.state() == ContainerState.RUNNING || existing.state() == ContainerState.STARTING)) {
            throw new ContainerStartException("task for container " + id + " is already running", null);
        }
        if (existing != null && existing.state() == ContainerState.STOPPED) {
            log.debug("task for container {} is stopped; deleting before restart", id);
            tasks.delete(id);
        }
        try {
            tasks.create(id);
            int pid = tasks.start(id);
            attachNetwork(id, pid);
            return pid;
        } catch (RuntimeException e) {
            try {
                if (tasks.exists(id)) {
                    tasks.delete(id);
                }
            } catch (RuntimeException cleanupFailure) {
                log.warn("failed to clean up task for container {} after start failure", id, cleanupFailure);
            }
            if (e instanceof ContainerdException mapped) {
                // already a typed library exception (e.g. ContainerNotFoundException from a
                // missing container, ContainerStartException) — do not re-wrap
                throw mapped;
            }
            throw new ContainerStartException("failed to start container " + id + ": " + e.getMessage(), e);
        }
    }

    /**
     * Attaches the container's network, if it asked for one.
     *
     * <p>A failure here is a failed start: the caller asked for a container on a network and would
     * otherwise be handed a running one that cannot reach anything. Detach runs first, because a
     * plugin that failed part-way may already have taken an address.
     */
    private void attachNetwork(String id, int pid) {
        String attachTo = networkOf(id);
        if (attachTo == null) {
            return;
        }
        try {
            log.debug("attaching container {} to network {} (pid={})", id, attachTo, pid);
            network.attach(id, attachTo, pid);
        } catch (RuntimeException e) {
            detachNetwork(id, pid);
            throw new ContainerStartException("container " + id + " started but could not be attached"
                    + " to network " + attachTo + ": " + e.getMessage(), e);
        }
    }

    /**
     * Detaches the container's network. Never throws: it runs on paths that are already tearing
     * something down, and a failure here must not replace the reason they were running.
     */
    private void detachNetwork(String id, int pid) {
        String detachFrom = networkOf(id);
        if (detachFrom == null) {
            return;
        }
        try {
            log.debug("detaching container {} from network {} (pid={})", id, detachFrom, pid);
            network.detach(id, detachFrom, pid);
        } catch (RuntimeException e) {
            log.warn("could not detach container {} from network {}; an address may be left"
                    + " allocated on the host", id, detachFrom, e);
        }
    }

    /** The network this container was created with, or null. Read from containerd, not remembered. */
    private String networkOf(String id) {
        if (network == null) {
            return null;
        }
        try {
            return containerOf(id).getLabelsMap().get(NETWORK_LABEL);
        } catch (RuntimeException e) {
            log.debug("could not read the network label for {}", id, e);
            return null;
        }
    }

    @Override
    public Optional<ExitStatus> stop(String id) {
        if (!tasks.exists(id)) {
            log.debug("stop: no task for container {} (idempotent)", id);
            return Optional.empty();
        }
        // Before anything is killed: the namespace CNI needs is the task's, and it goes when the
        // task does. Detaching afterwards would find nothing to undo and leak the address.
        detachNetwork(id, tasks.find(id).map(TaskInfo::pid).orElse(-1));

        ExitStatus status;
        try {
            status = terminate(id);
        } catch (TaskNotFoundException e) {
            // the task exited and was reaped while we were stopping it
            deleteTaskQuietly(id);
            return Optional.empty();
        } catch (ContainerStopException e) {
            throw e;
        } catch (RuntimeException e) {
            // The task is still alive, or the RPC failed. Deleting now would fail on a live task
            // and, from a finally block, would replace this exception with that failure.
            throw new ContainerStopException("failed to stop container " + id + ": " + e.getMessage(), e);
        }
        deleteTaskQuietly(id);
        return Optional.of(status);
    }

    /**
     * SIGTERM, wait for the grace period, then SIGKILL and wait again.
     *
     * <p>Both waits are bounded. SIGKILL cannot be caught, but it does not reach a process parked
     * in uninterruptible sleep — a wedged NFS or fuse mount is the usual cause — and such a task
     * never reaps. An unbounded wait there blocks the caller for the life of the process, so the
     * second grace period is spent and then the stop is reported as failed.
     */
    private ExitStatus terminate(String id) {
        tasks.kill(id, Signal.TERM);
        try {
            return tasks.wait(id, stopTimeout);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != io.grpc.Status.Code.DEADLINE_EXCEEDED) {
                throw e;
            }
        }
        log.debug("stop: container {} did not exit within {}, sending SIGKILL", id, stopTimeout);
        tasks.kill(id, Signal.KILL);
        try {
            return tasks.wait(id, stopTimeout);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != io.grpc.Status.Code.DEADLINE_EXCEEDED) {
                throw e;
            }
            throw new ContainerStopException("container " + id + " did not exit within " + stopTimeout
                    + " of SIGKILL; the task is most likely stuck in uninterruptible sleep."
                    + " Its state is left intact for inspection", e);
        }
    }

    /** Deletes the (now exited) task. A task already reaped by containerd is not an error. */
    private void deleteTaskQuietly(String id) {
        try {
            tasks.delete(id);
        } catch (TaskNotFoundException e) {
            // already gone
        }
    }

    @Override
    public void kill(String id, Signal signal) {
        tasks.kill(id, signal);
    }

    @Override
    public ExitStatus wait(String id) {
        return tasks.wait(id);
    }

    @Override
    public ExecResult exec(String id, List<String> command) {
        return exec(id, ExecSpec.builder().command(command).build());
    }

    @Override
    public ExecResult exec(String id, ExecSpec spec) {
        var task = tasks.find(id).orElseThrow(() -> new ExecException(
                "no task for container " + id + "; start the container before exec"));
        if (task.state() != ContainerState.RUNNING) {
            throw new ExecException("task for container " + id + " is not running (state=" + task.state() + ")");
        }
        // The environment the exec'd process should see is the one the container runs with, which
        // containerd already stores as part of the container's spec — no need to resolve the image
        // again. Without it nothing the image ships is on PATH, and its variables are all missing.
        StoredSpec container = StoredSpec.parse(containerSpecOf(id));

        String execId = "exec-" + UUID.randomUUID();
        var fifos = IoManager.createFifoSet(id + "-" + execId);
        // Open the read ends BEFORE the Exec RPC: open(2) blocks until the shim opens its write
        // end (which happens as the process spawns), so a process that exits immediately cannot
        // win the race and leave us with output we never read.
        var stdoutFuture = ioExecutor.submit(() -> IoManager.readFifo(fifos.stdout()));
        var stderrFuture = ioExecutor.submit(() -> IoManager.readFifo(fifos.stderr()));
        java.util.concurrent.Future<?> stdinFuture = null;
        try {
            tasks.exec(id, execId, spec, fifos, container);
            int pid = tasks.startExec(id, execId);
            // Always write and close stdin, even with nothing to send: the write end must be
            // opened and closed for the process to see EOF. Skipping it (as this did when
            // ExecSpec.stdin() was null) leaves a process that reads stdin blocked forever.
            byte[] stdin = spec.stdin() == null
                    ? new byte[0] : spec.stdin().getBytes(StandardCharsets.UTF_8);
            stdinFuture = ioExecutor.submit(() -> IoManager.writeFifo(fifos.stdin(), stdin));
            String stdout = stdoutFuture.get();
            String stderr = stderrFuture.get();
            ExitStatus status = tasks.waitExec(id, execId);
            log.debug("exec complete: containerId={} execId={} pid={} exitCode={}", id, execId, pid, status.code());
            return new ExecResult(status.code(), stdout, stderr);
        } catch (ContainerdException e) {
            throw new ExecException("exec failed for container " + id + ": " + e.getMessage(), e);
        } catch (ExecutionException e) {
            throw new ExecException("exec IO failed for container " + id, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecException("exec interrupted for container " + id, e);
        } finally {
            // If a reader never reached EOF (exec/start failed before the shim opened the FIFO
            // write ends), it is still blocked in open(2): pair it with a write end so it
            // completes. A reader that already hit EOF is left alone — opening a write end with
            // no reader to pair with would block.
            if (!stdoutFuture.isDone()) {
                unblockReader(fifos.stdout());
            }
            if (!stderrFuture.isDone()) {
                unblockReader(fifos.stderr());
            }
            // Mirror image: a writer still blocked in open(2) needs a read end to pair with.
            if (stdinFuture != null && !stdinFuture.isDone()) {
                unblockWriter(fifos.stdin());
            }
            try {
                tasks.deleteExec(id, execId);
            } catch (RuntimeException e) {
                log.warn("failed to delete exec process {} for container {}", execId, id, e);
            }
            IoManager.cleanup(fifos);
        }
    }

    /** Returns the OCI spec containerd stores on the container, or null if it cannot be read. */
    private com.google.protobuf.Any containerSpecOf(String id) {
        try {
            return stub.get(containerd.services.containers.v1.GetContainerRequest.newBuilder()
                    .setId(id).build()).getContainer().getSpec();
        } catch (StatusRuntimeException e) {
            log.debug("could not read the stored spec for {}; exec runs without its environment", id, e);
            return null;
        }
    }

    /** Opens and immediately closes a FIFO's write end so a reader blocked in open(2) proceeds to EOF. */
    private void unblockReader(Path fifo) {
        try (var out = Files.newOutputStream(fifo)) {
            // open-write-close: the paired reader now sees EOF and returns.
        } catch (IOException ignored) {
            // best effort — cleanup() removes the FIFO right after
        }
    }

    /** Opens and immediately closes a FIFO's read end so a writer blocked in open(2) proceeds. */
    private void unblockWriter(Path fifo) {
        try (var in = Files.newInputStream(fifo)) {
            // open-close: the paired writer's open(2) now returns.
        } catch (IOException ignored) {
            // best effort — cleanup() removes the FIFO right after
        }
    }

    /**
     * Shuts the IO virtual-thread pool down and gives in-flight exec IO a moment to drain.
     * The caller closes the gRPC channel right after, so without this an exec running
     * concurrently with close() would have its output truncated with no diagnostic.
     */
    public void close() {
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(IO_SHUTDOWN_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                log.warn("exec IO still in flight after {}; abandoning it", IO_SHUTDOWN_TIMEOUT);
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
    }
}
