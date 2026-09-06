package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.nanofaas.containerd.*;
import io.nanofaas.containerd.spi.Tasks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class TasksServiceImpl implements Tasks {

    private static final Logger log = LoggerFactory.getLogger(TasksServiceImpl.class);

    private final containerd.services.tasks.v1.TasksGrpc.TasksBlockingStub stub;
    private final containerd.services.containers.v1.ContainersGrpc.ContainersBlockingStub containers;
    private final SnapshotManager snapshots;
    private final String runtimeBinaryName;

    public TasksServiceImpl(ManagedChannel channel) {
        this(channel, null);
    }

    public TasksServiceImpl(ManagedChannel channel, String runtimeBinaryName) {
        this.stub = containerd.services.tasks.v1.TasksGrpc.newBlockingStub(channel);
        this.containers = containerd.services.containers.v1.ContainersGrpc.newBlockingStub(channel);
        this.snapshots = new SnapshotManager(channel);
        this.runtimeBinaryName = runtimeBinaryName;
    }

    @Override
    public void create(String containerId) {
        containerd.services.containers.v1.Container container;
        try {
            container = containers.get(containerd.services.containers.v1.GetContainerRequest.newBuilder()
                    .setId(containerId).build()).getContainer();
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.CONTAINER);
        }
        var mounts = snapshots.mounts(container.getSnapshotter(), container.getSnapshotKey());
        log.debug("task create: containerId={} snapshotter={} snapshotKey={}",
                containerId, container.getSnapshotter(), container.getSnapshotKey());

        var request = containerd.services.tasks.v1.CreateTaskRequest.newBuilder()
                .setContainerId(containerId)
                .addAllRootfs(mounts);
        if (runtimeBinaryName != null) {
            request.setOptions(TypeUrls.pack(containerd.runc.v1.Options.newBuilder()
                    .setBinaryName(runtimeBinaryName).build()));
        }
        try {
            stub.create(request.build());
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
        }
    }

    @Override
    public int start(String containerId) {
        try {
            return stub.start(containerd.services.tasks.v1.StartRequest.newBuilder()
                    .setContainerId(containerId).build()).getPid();
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
        }
    }

    @Override
    public void kill(String containerId, Signal signal) {
        log.debug("task kill: containerId={} signal={}", containerId, signal);
        try {
            stub.kill(containerd.services.tasks.v1.KillRequest.newBuilder()
                    .setContainerId(containerId)
                    .setSignal(signal.number())
                    .setAll(true)
                    .build());
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
        }
    }

    @Override
    public ExitStatus wait(String containerId) {
        return waitInternal(containerId, null);
    }

    @Override
    public ExitStatus wait(String containerId, java.time.Duration timeout) {
        return waitInternal(containerId, timeout);
    }

    private ExitStatus waitInternal(String containerId, java.time.Duration timeout) {
        try {
            var blockingStub = timeout == null
                    ? stub
                    : stub.withDeadlineAfter(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            var response = blockingStub.wait(containerd.services.tasks.v1.WaitRequest.newBuilder()
                    .setContainerId(containerId).build());
            return toExitStatus(response.getExitStatus(), response.hasExitedAt(), response.getExitedAt());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == io.grpc.Status.Code.DEADLINE_EXCEEDED) {
                throw e; // deliberately unmapped: callers detect the timeout by code
            }
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
        }
    }

    @Override
    public ExitStatus delete(String containerId) {
        try {
            var response = stub.delete(containerd.services.tasks.v1.DeleteTaskRequest.newBuilder()
                    .setContainerId(containerId).build());
            return toExitStatus(response.getExitStatus(), response.hasExitedAt(), response.getExitedAt());
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
        }
    }

    @Override
    public TaskInfo inspect(String containerId) {
        return find(containerId).orElseThrow(() ->
                new TaskNotFoundException("no task for container " + containerId, null));
    }

    @Override
    public List<TaskInfo> list() {
        try {
            return stub.list(containerd.services.tasks.v1.ListTasksRequest.getDefaultInstance())
                    .getTasksList().stream()
                    .map(TasksServiceImpl::toTaskInfo)
                    .toList();
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
        }
    }

    /**
     * Looks the task up in one round trip, empty if there is none (a STOPPED task still counts).
     *
     * <p>This is the primitive callers should use. Asking {@code exists()} and then
     * {@code inspect()} is two RPCs with a race between them: a task that exits and is reaped in
     * the gap makes the second call fail on a container the first said was there.
     */
    public Optional<TaskInfo> find(String containerId) {
        try {
            return Optional.of(toTaskInfo(stub.get(containerd.services.tasks.v1.GetRequest.newBuilder()
                    .setContainerId(containerId).build()).getProcess()));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
        }
    }

    /** Returns whether a task exists for the container (true even for STOPPED tasks). */
    public boolean exists(String containerId) {
        return find(containerId).isPresent();
    }

    private static TaskInfo toTaskInfo(containerd.v1.types.Process process) {
        return new TaskInfo(process.getContainerId(), process.getPid(),
                ProtoMapper.mapStatus(process.getStatus()), process.getExitStatus(),
                process.hasExitedAt() ? instant(process.getExitedAt()) : null);
    }

    private static Instant instant(com.google.protobuf.Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    /** Creates an exec process (OCI process spec + IO FIFO paths) inside a running task. */
    public void exec(String containerId, String execId, ExecSpec spec, IoManager.FifoSet fifos) {
        exec(containerId, execId, spec, fifos, StoredSpec.EMPTY);
    }

    /**
     * Creates an exec process, inheriting environment and working directory from the container's
     * own stored spec so the command sees what the container sees.
     */
    void exec(String containerId, String execId, ExecSpec spec, IoManager.FifoSet fifos,
              StoredSpec container) {
        var request = containerd.services.tasks.v1.ExecProcessRequest.newBuilder()
                .setContainerId(containerId)
                .setExecId(execId)
                .setStdin(fifos.stdin().toString())
                .setStdout(fifos.stdout().toString())
                .setStderr(fifos.stderr().toString())
                .setSpec(OciSpecBuilder.buildExecSpec(spec.command(), spec.environment(), spec.workingDir(),
                        container.env(), container.workingDir()))
                .build();
        log.debug("exec create: containerId={} execId={}", containerId, execId);
        try {
            stub.exec(request);
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
        }
    }

    /** Starts an exec process; returns its pid. */
    public int startExec(String containerId, String execId) {
        try {
            return stub.start(containerd.services.tasks.v1.StartRequest.newBuilder()
                    .setContainerId(containerId).setExecId(execId).build()).getPid();
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
        }
    }

    /** Blocks until an exec process exits; returns its exit status. */
    public ExitStatus waitExec(String containerId, String execId) {
        try {
            var response = stub.wait(containerd.services.tasks.v1.WaitRequest.newBuilder()
                    .setContainerId(containerId).setExecId(execId).build());
            return toExitStatus(response.getExitStatus(), response.hasExitedAt(), response.getExitedAt());
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
        }
    }

    /** Deletes an exec process. Idempotent — a missing exec (NOT_FOUND) is ignored. */
    public void deleteExec(String containerId, String execId) {
        try {
            stub.deleteProcess(containerd.services.tasks.v1.DeleteProcessRequest.newBuilder()
                    .setContainerId(containerId).setExecId(execId).build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != io.grpc.Status.Code.NOT_FOUND) {
                throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
            }
        }
    }

    /** Maps an exit response to {@link ExitStatus}, preserving sub-second precision (nanos). */
    private static ExitStatus toExitStatus(int exitStatus, boolean hasExitedAt,
                                           com.google.protobuf.Timestamp exitedAt) {
        return new ExitStatus(exitStatus, hasExitedAt ? instant(exitedAt) : null);
    }
}
