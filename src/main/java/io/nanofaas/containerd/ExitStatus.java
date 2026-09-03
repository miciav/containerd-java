package io.nanofaas.containerd;

import java.time.Instant;

/** Exit code of a stopped task and the moment it exited. */
public record ExitStatus(int code, Instant exitedAt) {
}
