package io.nanofaas.containerd;

import java.time.Instant;

/**
 * Observed state of a task, the running instance of a container.
 *
 * @param containerId container the task belongs to
 * @param pid process id of the init process
 * @param state current task state
 * @param exitCode exit code, meaningful only once {@code state} is {@link ContainerState#STOPPED}
 * @param exitedAt when the task exited, or {@code null} if it has not exited or containerd reported no timestamp
 */
public record TaskInfo(String containerId, int pid, ContainerState state, int exitCode, Instant exitedAt) {
}
