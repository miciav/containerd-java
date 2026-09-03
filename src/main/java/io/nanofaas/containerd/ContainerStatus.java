package io.nanofaas.containerd;

import java.time.Instant;

/**
 * Observed status of a container's task. {@code pid} is {@code -1} when unknown;
 * {@code exitStatus} is {@code null} unless {@code state} is {@link ContainerState#STOPPED}.
 */
public record ContainerStatus(String id, String image, ContainerState state, int pid, ExitStatus exitStatus, String snapshotKey, Instant createdAt) {
}
