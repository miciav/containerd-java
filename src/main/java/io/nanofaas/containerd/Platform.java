package io.nanofaas.containerd;

import java.util.Locale;

/** Operating system and architecture pair used to select images and platforms. */
public record Platform(String os, String architecture) {

    public static Platform linuxAmd64() {
        return new Platform("linux", "amd64");
    }

    /** The platform of the host this JVM runs on (the default for pulls, like docker). */
    public static Platform host() {
        return fromOsArch(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /** Normalizes a JVM os.name/os.arch pair to OCI os/architecture values. */
    public static Platform fromOsArch(String osName, String arch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String osNorm = os.startsWith("linux") ? "linux" : os.startsWith("mac") ? "darwin"
                : os.startsWith("windows") ? "windows" : os;
        String archNorm = switch (arch) {
            case "amd64", "x86_64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            default -> arch;
        };
        return new Platform(osNorm, archNorm);
    }
}
