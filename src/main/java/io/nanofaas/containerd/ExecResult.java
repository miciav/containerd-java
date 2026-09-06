package io.nanofaas.containerd;

/**
 * Result of an exec: exit code plus captured output.
 *
 * @param exitCode exit code of the exec'd process
 * @param stdout everything the process wrote to stdout, decoded as UTF-8
 * @param stderr everything the process wrote to stderr, decoded as UTF-8
 */
public record ExecResult(int exitCode, String stdout, String stderr) {
}
