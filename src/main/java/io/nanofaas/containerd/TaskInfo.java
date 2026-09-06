package io.nanofaas.containerd;

import java.time.Instant;

/**
 * Observed state of a task (the running instance of a container). {@code exitCode} and
 * {@code exitedAt} are only meaningful once {@code state} is {@link ContainerState#STOPPED};
 * {@code exitedAt} is null when containerd did not report one.
 */
public record TaskInfo(String containerId, int pid, ContainerState state, int exitCode, Instant exitedAt) {
}
