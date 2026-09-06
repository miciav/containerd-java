package io.nanofaas.containerd;

import java.time.Instant;

/**
 * A containerd event envelope, decoded where the payload type is known.
 *
 * @param topic event topic, for example {@code /tasks/exit}
 * @param namespace namespace the event was published in
 * @param timestamp when containerd published the event, or {@code null} if it reported none
 * @param taskEvent decoded payload for task start/exit/delete events, {@code null} for every other topic
 */
public record Event(String topic, String namespace, Instant timestamp, TaskEvent taskEvent) {
}
