package io.nanofaas.containerd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformTest {

    @Test
    void fromOsArchNormalizesJvmValuesToOciValues() {
        assertThat(Platform.fromOsArch("Linux", "aarch64")).isEqualTo(new Platform("linux", "arm64"));
        assertThat(Platform.fromOsArch("Linux", "amd64")).isEqualTo(new Platform("linux", "amd64"));
        assertThat(Platform.fromOsArch("Linux", "x86_64")).isEqualTo(new Platform("linux", "amd64"));
        assertThat(Platform.fromOsArch("Mac OS X", "aarch64")).isEqualTo(new Platform("darwin", "arm64"));
        assertThat(Platform.fromOsArch("Windows 11", "amd64")).isEqualTo(new Platform("windows", "amd64"));
    }

    @Test
    void hostPlatformMatchesRunningJvm() {
        var host = Platform.host();
        assertThat(host.os()).isNotBlank();
        assertThat(host.architecture()).isNotBlank();
    }
}
