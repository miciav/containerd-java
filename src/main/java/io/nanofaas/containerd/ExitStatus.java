package io.nanofaas.containerd;

import java.time.Instant;

/**
 * Exit code of a stopped task and the moment it exited.
 *
 * @param code process exit code
 * @param exitedAt when the process exited, or {@code null} if containerd reported no timestamp
 */
public record ExitStatus(int code, Instant exitedAt) {
}
