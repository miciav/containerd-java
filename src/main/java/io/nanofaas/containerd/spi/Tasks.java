package io.nanofaas.containerd.spi;

import io.nanofaas.containerd.ExitStatus;
import io.nanofaas.containerd.Signal;
import io.nanofaas.containerd.TaskInfo;

import java.util.List;

/**
 * Low-level task operations. A task is the running instance of a container.
 * Most users should prefer {@link Containers#start}/{@link Containers#stop};
 * these exist for NanoFaaS fast-path orchestration.
 */
public interface Tasks {

    /** Creates a task for an existing container (task state CREATED, not running). */
    void create(String containerId);

    /** Starts a created task; returns the init process pid. */
    int start(String containerId);

    void kill(String containerId, Signal signal);

    /** Blocks until the task exits. */
    ExitStatus wait(String containerId);

    /**
     * Blocks until the task exits or the timeout elapses. Throws the raw gRPC
     * {@link io.grpc.StatusRuntimeException} with code {@code DEADLINE_EXCEEDED} on timeout
     * (deliberately unmapped, so callers can detect it); other failures are mapped as usual.
     */
    ExitStatus wait(String containerId, java.time.Duration timeout);

    /** Deletes a (stopped) task, releasing its state. */
    ExitStatus delete(String containerId);

    TaskInfo inspect(String containerId);

    List<TaskInfo> list();
}
