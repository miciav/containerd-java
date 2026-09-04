package io.nanofaas.containerd;

import java.time.Instant;

/** A containerd event envelope, decoded where the payload type is known. */
public record Event(String topic, String namespace, Instant timestamp, TaskEvent taskEvent) {
}
