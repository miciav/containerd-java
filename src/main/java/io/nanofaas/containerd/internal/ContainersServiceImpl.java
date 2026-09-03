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
    private final String snapshotter;
    private final String runtimeName;

    public ContainersServiceImpl(ManagedChannel channel, String snapshotter, String runtimeName, String runtimeBinaryName) {
        this.channel = channel;
        this.stub = containerd.services.containers.v1.ContainersGrpc.newBlockingStub(channel);
        this.snapshots = new SnapshotManager(channel, snapshotter);
        this.rootfsResolver = new ImageRootfsResolver(channel);
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
        var container = stub.get(containerd.services.containers.v1.GetContainerRequest.newBuilder()
                .setId(id).build()).getContainer();
        return new ContainerStatus(
                container.getId(),
                container.getImage(),
                ContainerState.UNKNOWN, // task state filled in by Task 10
                -1,
                null,
                container.getSnapshotKey(),
                ProtoMapper.map(container).createdAt());
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
        log.debug("container remove: id={} removeSnapshot={} force={}", id, options.removeSnapshot(), options.force());
        if (hasTask(id)) {
            if (!options.force()) {
                throw new ContainerdException("container " + id + " still running, use RemoveOptions.force(true)");
            }
            stop(id); // full stop flow (SIGTERM → wait → SIGKILL → task delete) lands in Task 10
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
            snapshots.remove(container.getSnapshotKey()); // idempotent
        }
        log.debug("container remove complete: id={}", id);
    }

    private boolean hasTask(String containerId) {
        try {
            containerd.services.tasks.v1.TasksGrpc.newBlockingStub(channel)
                    .get(containerd.services.tasks.v1.GetRequest.newBuilder()
                            .setContainerId(containerId).build());
            return true;
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                return false;
            }
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
        }
    }

    @Override
    public int start(String id) {
        throw new UnsupportedOperationException("implemented in Task 10");
    }

    @Override
    public Optional<ExitStatus> stop(String id) {
        throw new UnsupportedOperationException("implemented in Task 10");
    }

    @Override
    public void kill(String id, Signal signal) {
        throw new UnsupportedOperationException("implemented in Task 10");
    }

    @Override
    public ExitStatus wait(String id) {
        throw new UnsupportedOperationException("implemented in Task 10");
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
