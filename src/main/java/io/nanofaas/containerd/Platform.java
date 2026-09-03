package io.nanofaas.containerd;

/** Operating system and architecture pair used to select images and platforms. */
public record Platform(String os, String architecture) {

    public static Platform linuxAmd64() {
        return new Platform("linux", "amd64");
    }
}
