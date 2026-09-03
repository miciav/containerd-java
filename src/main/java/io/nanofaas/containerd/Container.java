package io.nanofaas.containerd;

import java.time.Instant;
import java.util.Map;

/** A container as stored by containerd. */
public record Container(String id, String image, String snapshotter, String snapshotKey, Instant createdAt, Map<String, String> labels) {
}
