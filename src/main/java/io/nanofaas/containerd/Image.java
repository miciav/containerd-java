package io.nanofaas.containerd;

import java.time.Instant;
import java.util.Map;

/** An image as stored by containerd. */
public record Image(String name, String digest, long size, Instant createdAt, Map<String, String> labels) {
}
