package io.nanofaas.containerd;

import java.time.Instant;

/**
 * Combined view of a container and the state of its task, if it has one.
 *
 * @param id container identifier
 * @param image reference of the image the container was created from
 * @param state state of the container's task, or {@link ContainerState#UNKNOWN} when there is no task
 * @param pid process id of the task's init process, or {@code -1} when unknown
 * @param exitStatus how the task exited, or {@code null} unless {@code state} is {@link ContainerState#STOPPED}
 * @param snapshotKey key of the snapshot holding the root filesystem
 * @param createdAt when containerd created the container
 */
public record ContainerStatus(String id, String image, ContainerState state, int pid, ExitStatus exitStatus, String snapshotKey, Instant createdAt) {
}
