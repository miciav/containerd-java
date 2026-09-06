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

    /**
     * Creates a task for an existing container, leaving it CREATED rather than running.
     *
     * @param containerId container to create the task for
     */
    void create(String containerId);

    /**
     * Starts a created task.
     *
     * @param containerId container whose task to start
     * @return pid of the init process
     */
    int start(String containerId);

    /**
     * Sends a signal to every process in the task.
     *
     * @param containerId container whose task to signal
     * @param signal signal to send
     */
    void kill(String containerId, Signal signal);

    /**
     * Blocks until the task exits.
     *
     * @param containerId container whose task to wait for
     * @return how it exited
     */
    ExitStatus wait(String containerId);

    /**
     * Blocks until the task exits or the timeout elapses. Throws the raw gRPC
     * {@link io.grpc.StatusRuntimeException} with code {@code DEADLINE_EXCEEDED} on timeout
     * (deliberately unmapped, so callers can detect it); other failures are mapped as usual.
     *
     * @param containerId container whose task to wait for
     * @param timeout how long to wait
     * @return how it exited
     */
    ExitStatus wait(String containerId, java.time.Duration timeout);

    /**
     * Deletes a stopped task, releasing its state.
     *
     * @param containerId container whose task to delete
     * @return how it exited
     */
    ExitStatus delete(String containerId);

    /**
     * Looks the task up.
     *
     * @param containerId container whose task to inspect
     * @return the task's current state
     * @throws io.nanofaas.containerd.TaskNotFoundException if the container has no task
     */
    TaskInfo inspect(String containerId);

    /** {@return every task in the client's namespace} */
    List<TaskInfo> list();
}
