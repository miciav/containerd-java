package io.nanofaas.containerd;

import java.util.Locale;

/**
 * Operating system and architecture pair used to select images and platforms.
 *
 * @param os OCI operating system name, for example {@code linux}
 * @param architecture OCI architecture name, for example {@code amd64} or {@code arm64}
 */
public record Platform(String os, String architecture) {

    /** {@return the {@code linux/amd64} platform} */
    public static Platform linuxAmd64() {
        return new Platform("linux", "amd64");
    }

    /** {@return the platform of the host this JVM runs on, the default for pulls as in docker} */
    public static Platform host() {
        return fromOsArch(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /**
     * Normalizes a JVM {@code os.name}/{@code os.arch} pair to OCI values.
     *
     * @param osName value of the {@code os.name} system property
     * @param arch value of the {@code os.arch} system property
     * @return the corresponding OCI platform
     */
    public static Platform fromOsArch(String osName, String arch) {
        String os = osName.trim().toLowerCase(Locale.ROOT);
        String osNorm = os.startsWith("linux") ? "linux" : os.startsWith("mac") ? "darwin"
                : os.startsWith("windows") ? "windows" : os;
        // Normalize the architecture the same way as the os, rather than matching it case-sensitively.
        String archLower = arch.trim().toLowerCase(Locale.ROOT);
        String archNorm = switch (archLower) {
            case "amd64", "x86_64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            case "x86", "i386", "i486", "i586", "i686" -> "386";
            default -> archLower;
        };
        return new Platform(osNorm, archNorm);
    }
}
