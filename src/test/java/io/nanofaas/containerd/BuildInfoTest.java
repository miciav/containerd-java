package io.nanofaas.containerd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildInfoTest {
    @Test
    void toolchainRunsJava21OrNewer() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }
}
