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

public final class ContainersServiceImpl implements Containers {

    private static final Logger log = LoggerFactory.getLogger(ContainersServiceImpl.class);

    /** Grace period between SIGTERM and SIGKILL in {@link #stop(String)}. */
    static final java.time.Duration STOP_TIMEOUT = java.time.Duration.ofSeconds(10);

    private final ManagedChannel channel;
    private final containerd.services.containers.v1.ContainersGrpc.ContainersBlockingStub stub;
    private final SnapshotManager snapshots;
    private final ImageRootfsResolver rootfsResolver;
    private final TasksServiceImpl tasks;
    private final String snapshotter;
    private final String runtimeName;
    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ContainersServiceImpl(ManagedChannel channel, String snapshotter, String runtimeName, String runtimeBinaryName) {
        this.channel = channel;
        this.stub = containerd.services.containers.v1.ContainersGrpc.newBlockingStub(channel);
        this.snapshots = new SnapshotManager(channel);
        this.rootfsResolver = new ImageRootfsResolver(channel);
        this.tasks = new TasksServiceImpl(channel, runtimeBinaryName);
        this.snapshotter = snapshotter;
        this.runtimeName = runtimeName;
    }

    @Override
    public Container create(ContainerSpec spec) {
        ProtoMapper.requireValidId(spec.id());
        log.debug("container create start: id={} image={}", spec.id(), spec.image());

        String parentChainId;
        try {
            parentChainId = rootfsResolver.resolveChainId(spec.image());
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.IMAGE);
        }
        prepareSnapshotOrThrow(spec.id(), parentChainId);

        try {
            var container = containerd.services.containers.v1.Container.newBuilder()
                    .setId(spec.id())
                    .setImage(spec.image())
                    .setSnapshotter(snapshotter)
                    .setSnapshotKey(spec.id())
                    .setSpec(OciSpecBuilder.buildContainerSpec(spec))
                    .setRuntime(containerd.services.containers.v1.Container.Runtime.newBuilder()
                            .setName(runtimeName))
                    .putLabels("containerd.io/gc.ref.snapshot." + snapshotter, spec.id())
                    .putAllLabels(spec.labels())
                    .build();
            var created = stub.create(containerd.services.containers.v1.CreateContainerRequest.newBuilder()
                    .setContainer(container).build()).getContainer();
            log.debug("container create complete: id={}", spec.id());
            return ProtoMapper.map(created);
        } catch (StatusRuntimeException e) {
            try {
                snapshots.remove(snapshotter, spec.id());
            } catch (StatusRuntimeException cleanupFailure) {
                log.warn("failed to clean up snapshot {} after container create failure", spec.id(), cleanupFailure);
            }
            if (e.getStatus().getCode() == io.grpc.Status.Code.ALREADY_EXISTS) {
                throw new ContainerAlreadyExistsException("container " + spec.id() + " already exists", e);
            }
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.CONTAINER);
        }
    }

    /**
     * Prepares the container's snapshot. ALREADY_EXISTS means the id is already taken: if the
     * container exists this is a duplicate create; if it does not, a stale snapshot from a
     * previous partial create is removed and prepare is retried once.
     */
    private void prepareSnapshotOrThrow(String id, String parentChainId) {
        try {
            snapshots.prepare(snapshotter, id, parentChainId);
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
                snapshots.prepare(snapshotter, id, parentChainId);
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
        if (tasks.exists(id)) {
            var task = tasks.inspect(id);
            state = task.state();
            pid = task.pid();
            if (state == ContainerState.STOPPED) {
                exitStatus = new ExitStatus(task.exitCode(), null);
            }
        }
        return new ContainerStatus(container.getId(), container.getImage(), state, pid,
                exitStatus, container.getSnapshotKey(), ProtoMapper.map(container).createdAt());
    }

    @Override
    public List<Container> list() {
        return stub.list(containerd.services.containers.v1.ListContainersRequest.getDefaultInstance())
                .getContainersList().stream()
                .map(ProtoMapper::map)
                .toList();
    }

    @Override
    public void remove(String id, RemoveOptions options) {
        log.debug("container remove: id={} removeSnapshot={} force={}",
                id, options.removeSnapshot(), options.force());
        if (tasks.exists(id)) {
            var task = tasks.inspect(id);
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

        stub.delete(containerd.services.containers.v1.DeleteContainerRequest.newBuilder()
                .setId(id).build());

        if (options.removeSnapshot() && !container.getSnapshotKey().isEmpty()) {
            snapshots.remove(container.getSnapshotter(), container.getSnapshotKey()); // idempotent
        }
        log.debug("container remove complete: id={}", id);
    }

    @Override
    public int start(String id) {
        TaskInfo existing = null;
        if (tasks.exists(id)) {
            existing = tasks.inspect(id);
        }
        if (existing != null && (existing.state() == ContainerState.RUNNING || existing.state() == ContainerState.STARTING)) {
            throw new ContainerStartException("task for container " + id + " is already running", null);
        }
        if (existing != null && existing.state() == ContainerState.STOPPED) {
            log.debug("task for container {} is stopped; deleting before restart", id);
            tasks.delete(id);
        }
        try {
            tasks.create(id);
            return tasks.start(id);
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

    @Override
    public Optional<ExitStatus> stop(String id) {
        if (!tasks.exists(id)) {
            log.debug("stop: no task for container {} (idempotent)", id);
            return Optional.empty();
        }
        try {
            try {
                tasks.kill(id, Signal.TERM);
            } catch (TaskNotFoundException e) {
                return Optional.empty();
            }
            try {
                return Optional.of(tasks.wait(id, STOP_TIMEOUT));
            } catch (TaskNotFoundException e) {
                return Optional.empty();
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == io.grpc.Status.Code.DEADLINE_EXCEEDED) {
                    log.debug("stop: container {} did not exit in time, sending SIGKILL", id);
                    tasks.kill(id, Signal.KILL);
                    return Optional.of(tasks.wait(id));
                }
                throw e;
            } finally {
                try {
                    tasks.delete(id);
                } catch (TaskNotFoundException e) {
                    // already gone
                }
            }
        } catch (RuntimeException e) {
            throw new ContainerStopException("failed to stop container " + id + ": " + e.getMessage(), e);
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
        if (!tasks.exists(id)) {
            throw new ExecException("no task for container " + id + "; start the container before exec");
        }
        var task = tasks.inspect(id);
        if (task.state() != ContainerState.RUNNING) {
            throw new ExecException("task for container " + id + " is not running (state=" + task.state() + ")");
        }

        String execId = "exec-" + UUID.randomUUID();
        var fifos = IoManager.createFifoSet(id + "-" + execId);
        // Open the read ends BEFORE the Exec RPC: open(2) blocks until the shim opens its write
        // end (which happens as the process spawns), so a process that exits immediately cannot
        // win the race and leave us with output we never read.
        var stdoutFuture = ioExecutor.submit(() -> IoManager.readFifo(fifos.stdout()));
        var stderrFuture = ioExecutor.submit(() -> IoManager.readFifo(fifos.stderr()));
        try {
            tasks.exec(id, execId, spec, fifos);
            int pid = tasks.startExec(id, execId);
            if (spec.stdin() != null) {
                ioExecutor.submit(() -> IoManager.writeFifo(fifos.stdin(), spec.stdin().getBytes(StandardCharsets.UTF_8)));
            }
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
            try {
                tasks.deleteExec(id, execId);
            } catch (RuntimeException e) {
                log.warn("failed to delete exec process {} for container {}", execId, id, e);
            }
            IoManager.cleanup(fifos);
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

    /** Shuts down the IO virtual-thread pool. Called by the owning {@code ContainerdClient}. */
    public void close() {
        ioExecutor.shutdown();
    }
}
