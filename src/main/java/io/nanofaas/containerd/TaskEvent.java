package io.nanofaas.containerd;

/**
 * Typed view of a task event payload (start / exit / delete).
 *
 * @param containerId container the task belongs to
 * @param pid process id the event refers to
 * @param exitStatus exit code, or {@code null} for events that carry none such as {@code /tasks/start}
 */
public record TaskEvent(String containerId, int pid, Integer exitStatus) {
}
