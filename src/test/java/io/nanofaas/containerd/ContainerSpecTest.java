package io.nanofaas.containerd;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerSpecTest {

    @Test
    void builderPopulatesFields() {
        var spec = ContainerSpec.builder()
                .id("fn-42")
                .image("docker.io/my/function:v1")
                .command(List.of("/function"))
                .environment(Map.of("PORT", "8080"))
                .cpuQuotaMicros(50000)
                .memoryLimitBytes(128L * 1024 * 1024)
                .build();

        assertThat(spec.id()).isEqualTo("fn-42");
        assertThat(spec.command()).containsExactly("/function");
        assertThat(spec.environment()).containsEntry("PORT", "8080");
        assertThat(spec.cpuQuotaMicros()).isEqualTo(50000);
        assertThat(spec.memoryLimitBytes()).isEqualTo(128L * 1024 * 1024);
    }

    @Test
    void idIsRequired() {
        assertThatThrownBy(() -> ContainerSpec.builder().image("alpine").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }
}
