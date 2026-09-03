package io.nanofaas.containerd;

/** Result of an exec: exit code plus captured output. */
public record ExecResult(int exitCode, String stdout, String stderr) {
}
