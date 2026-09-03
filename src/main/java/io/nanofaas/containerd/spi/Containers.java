package io.nanofaas.containerd.spi;

import io.nanofaas.containerd.*;

import java.util.List;

/**
 * Container lifecycle operations. A containerd container is metadata + spec + snapshot; running
 * state lives in a task. {@link #start} creates the task; {@link #stop} tears it down.
 */
public interface Containers {

    Container create(ContainerSpec spec);

    /** Combined view of the container metadata and its task state, if any. */
    ContainerStatus inspect(String id);

    List<Container> list();

    /**
     * Removes a container. Idempotent — a missing container is ignored. If a task is still running,
     * removal throws unless {@link RemoveOptions#force()} is set (which stops the task first).
     */
    void remove(String id, RemoveOptions options);

    /** Creates (if needed) and starts the container's task. Returns the init process pid. */
    int start(String id);

    /**
     * Stops a running container: SIGTERM, wait up to 10s, then SIGKILL, then task delete.
     * Idempotent — a container with no task returns an empty {@link java.util.Optional}.
     */
    java.util.Optional<ExitStatus> stop(String id);

    void kill(String id, Signal signal);

    /** Blocks until the container's init process exits. */
    ExitStatus wait(String id);

    ExecResult exec(String id, List<String> command);

    ExecResult exec(String id, ExecSpec spec);
}
