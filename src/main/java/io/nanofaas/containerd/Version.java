package io.nanofaas.containerd;

/** Version information reported by containerd. */
public record Version(String version, String revision) {
}
