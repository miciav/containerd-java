package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.nanofaas.containerd.*;
import io.nanofaas.containerd.spi.Containers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

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

    public ContainersServiceImpl(ManagedChannel channel, String snapshotter, String runtimeName, String runtimeBinaryName) {
        this.channel = channel;
        this.stub = containerd.services.containers.v1.ContainersGrpc.newBlockingStub(channel);
        this.snapshots = new SnapshotManager(channel, snapshotter);
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
                snapshots.remove(spec.id());
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
            snapshots.prepare(id, parentChainId);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != io.grpc.Status.Code.ALREADY_EXISTS) {
                throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.SNAPSHOT);
            }
            if (containerExists(id)) {
                throw new ContainerAlreadyExistsException("container " + id + " already exists", e);
            }
            log.warn("removing stale snapshot {} (no container with that id) and retrying prepare", id);
            try {
                snapshots.remove(id);
                snapshots.prepare(id, parentChainId);
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
            snapshots.forSnapshotter(container.getSnapshotter()).remove(container.getSnapshotKey()); // idempotent
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
        throw new UnsupportedOperationException("implemented in Task 11");
    }

    @Override
    public ExecResult exec(String id, ExecSpec spec) {
        throw new UnsupportedOperationException("implemented in Task 11");
    }
}
