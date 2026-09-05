package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.nanofaas.containerd.*;
import io.nanofaas.containerd.spi.Tasks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

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
        this.snapshots = new SnapshotManager(channel, null); // snapshotter resolved per container
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
        var mounts = snapshots.forSnapshotter(container.getSnapshotter()).mounts(container.getSnapshotKey());
        log.debug("task create: containerId={} snapshotter={} snapshotKey={}",
                containerId, container.getSnapshotter(), container.getSnapshotKey());

        var request = containerd.services.tasks.v1.CreateTaskRequest.newBuilder()
                .setContainerId(containerId)
                .addAllRootfs(mounts);
        if (runtimeBinaryName != null) {
            var options = containerd.runc.v1.Options.newBuilder().setBinaryName(runtimeBinaryName).build();
            request.setOptions(com.google.protobuf.Any.newBuilder()
                    .setTypeUrl(options.getDescriptorForType().getFullName())
                    .setValue(options.toByteString()));
        }
        try {
            stub.create(request.build());
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
        }
    }

    @Override
    public int start(String containerId) {
        var response = stub.start(containerd.services.tasks.v1.StartRequest.newBuilder()
                .setContainerId(containerId).build());
        return response.getPid();
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
        var process = stub.get(containerd.services.tasks.v1.GetRequest.newBuilder()
                .setContainerId(containerId).build()).getProcess();
        return new TaskInfo(containerId, process.getPid(),
                ProtoMapper.mapStatus(process.getStatus().getNumber()), process.getExitStatus());
    }

    @Override
    public List<TaskInfo> list() {
        return stub.list(containerd.services.tasks.v1.ListTasksRequest.getDefaultInstance())
                .getTasksList().stream()
                .map(p -> new TaskInfo(p.getContainerId(), p.getPid(),
                        ProtoMapper.mapStatus(p.getStatus().getNumber()), p.getExitStatus()))
                .toList();
    }

    /** Returns whether a task exists for the container (true even for STOPPED tasks). */
    public boolean exists(String containerId) {
        try {
            stub.get(containerd.services.tasks.v1.GetRequest.newBuilder()
                    .setContainerId(containerId).build());
            return true;
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                return false;
            }
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.TASK);
        }
    }

    /** Creates an exec process (OCI process spec + IO FIFO paths) inside a running task. */
    public void exec(String containerId, String execId, ExecSpec spec, IoManager.FifoSet fifos) {
        var request = containerd.services.tasks.v1.ExecProcessRequest.newBuilder()
                .setContainerId(containerId)
                .setExecId(execId)
                .setStdin(fifos.stdin().toString())
                .setStdout(fifos.stdout().toString())
                .setStderr(fifos.stderr().toString())
                .setSpec(OciSpecBuilder.buildExecSpec(spec.command(), spec.environment(), spec.workingDir()))
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
        return new ExitStatus(exitStatus,
                hasExitedAt ? Instant.ofEpochSecond(exitedAt.getSeconds(), exitedAt.getNanos()) : null);
    }
}
