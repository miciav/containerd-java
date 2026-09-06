package io.nanofaas.containerd;

/**
 * containerd's reported version.
 *
 * @param version version string, for example {@code v2.2.1}
 * @param revision git revision containerd was built from
 */
public record Version(String version, String revision) {
}
