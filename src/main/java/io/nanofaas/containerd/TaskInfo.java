package io.nanofaas.containerd;

/** Observed state of a task (the running instance of a container). */
public record TaskInfo(String containerId, int pid, ContainerState state, int exitCode) {
}
