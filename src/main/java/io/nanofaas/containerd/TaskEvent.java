package io.nanofaas.containerd;

/**
 * Typed view of a task event payload (start / exit / delete). {@code exitStatus} is null for
 * events that carry no exit code (e.g. {@code /tasks/start}).
 */
public record TaskEvent(String containerId, int pid, Integer exitStatus) {
}
