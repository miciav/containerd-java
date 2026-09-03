package io.nanofaas.containerd.internal;

import com.google.protobuf.util.JsonFormat;
import io.nanofaas.containerd.ContainerSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OciSpecBuilderTest {

    private static final String SPEC_TYPE_URL = "types.containerd.io/opencontainers/runtime-spec/1/Spec";
    private static final String PROCESS_TYPE_URL = "types.containerd.io/opencontainers/runtime-spec/1/Process";

    private static com.google.protobuf.Struct parse(com.google.protobuf.Any any) {
        try {
            var struct = com.google.protobuf.Struct.newBuilder();
            JsonFormat.parser().merge(any.getValue().toStringUtf8(), struct);
            return struct.build();
        } catch (Exception e) {
            throw new AssertionError("spec JSON does not parse: " + any.getValue().toStringUtf8(), e);
        }
    }

    @Test
    void containerSpecUsesContainerdTypeUrlAndJsonValue() {
        var any = OciSpecBuilder.buildContainerSpec(ContainerSpec.builder()
                .id("test-1")
                .image("docker.io/library/alpine:latest")
                .command(List.of("/bin/sh", "-c", "while true; do sleep 10; done"))
                .build());

        assertThat(any.getTypeUrl()).isEqualTo(SPEC_TYPE_URL);
        assertThat(any.getValue().size()).isGreaterThan(0);
        parse(any); // must not throw
    }

    @Test
    void containerSpecIncludesProcessRootAndLinuxDefaults() {
        var spec = parse(OciSpecBuilder.buildContainerSpec(ContainerSpec.builder()
                .id("test-1")
                .image("alpine")
                .command(List.of("/bin/sh"))
                .workingDir("/work")
                .environment(Map.of("PORT", "8080"))
                .hostname("myhost")
                .user("1000:1000")
                .build()));

        assertThat(spec.getFieldsOrThrow("ociVersion").getStringValue()).isEqualTo("1.2.0");

        var process = spec.getFieldsOrThrow("process").getStructValue();
        assertThat(process.getFieldsOrThrow("cwd").getStringValue()).isEqualTo("/work");
        assertThat(process.getFieldsOrThrow("args").getListValue().getValues(0).getStringValue()).isEqualTo("/bin/sh");
        assertThat(process.getFieldsOrThrow("env").getListValue().getValuesList())
                .extracting(com.google.protobuf.Value::getStringValue)
                .contains("PORT=8080");
        assertThat(process.getFieldsOrThrow("terminal").getBoolValue()).isFalse();

        var user = process.getFieldsOrThrow("user").getStructValue();
        assertThat(user.getFieldsOrThrow("uid").getNumberValue()).isEqualTo(1000);
        assertThat(user.getFieldsOrThrow("gid").getNumberValue()).isEqualTo(1000);

        var root = spec.getFieldsOrThrow("root").getStructValue();
        assertThat(root.getFieldsOrThrow("path").getStringValue()).isEqualTo("rootfs");
        assertThat(root.getFieldsOrThrow("readonly").getBoolValue()).isFalse();

        assertThat(spec.getFieldsOrThrow("hostname").getStringValue()).isEqualTo("myhost");

        var linux = spec.getFieldsOrThrow("linux").getStructValue();
        var namespaces = linux.getFieldsOrThrow("namespaces").getListValue().getValuesList();
        assertThat(namespaces).extracting(v -> v.getStructValue().getFieldsOrThrow("type").getStringValue())
                .containsExactlyInAnyOrder("pid", "network", "ipc", "uts", "mount");
    }

    @Test
    void containerSpecIncludesResourceLimits() {
        var spec = parse(OciSpecBuilder.buildContainerSpec(ContainerSpec.builder()
                .id("test-1")
                .image("alpine")
                .cpuShares(512)
                .cpuQuotaMicros(50000)
                .cpuPeriodMicros(100000)
                .memoryLimitBytes(128L * 1024 * 1024)
                .memorySwapLimitBytes(256L * 1024 * 1024)
                .pidsLimit(100)
                .build()));

        var resources = spec.getFieldsOrThrow("linux").getStructValue()
                .getFieldsOrThrow("resources").getStructValue();

        var cpu = resources.getFieldsOrThrow("cpu").getStructValue();
        assertThat(cpu.getFieldsOrThrow("shares").getNumberValue()).isEqualTo(512);
        assertThat(cpu.getFieldsOrThrow("quota").getNumberValue()).isEqualTo(50000);
        assertThat(cpu.getFieldsOrThrow("period").getNumberValue()).isEqualTo(100000);

        var memory = resources.getFieldsOrThrow("memory").getStructValue();
        assertThat(memory.getFieldsOrThrow("limit").getNumberValue()).isEqualTo(128L * 1024 * 1024);
        assertThat(memory.getFieldsOrThrow("swap").getNumberValue()).isEqualTo(256L * 1024 * 1024);

        assertThat(resources.getFieldsOrThrow("pids").getStructValue()
                .getFieldsOrThrow("limit").getNumberValue()).isEqualTo(100);
    }

    @Test
    void readonlyRootfsIsPropagated() {
        var spec = parse(OciSpecBuilder.buildContainerSpec(ContainerSpec.builder()
                .id("test-1")
                .image("alpine")
                .readonlyRootfs(true)
                .build()));
        assertThat(spec.getFieldsOrThrow("root").getStructValue().getFieldsOrThrow("readonly").getBoolValue()).isTrue();
    }

    @Test
    void execSpecUsesProcessTypeUrlAndJson() {
        var any = OciSpecBuilder.buildExecSpec(List.of("/bin/echo", "hello"), Map.of("A", "B"), "/tmp");
        assertThat(any.getTypeUrl()).isEqualTo(PROCESS_TYPE_URL);
        var process = parse(any);
        assertThat(process.getFieldsOrThrow("args").getListValue().getValuesList())
                .extracting(com.google.protobuf.Value::getStringValue)
                .containsExactly("/bin/echo", "hello");
        assertThat(process.getFieldsOrThrow("cwd").getStringValue()).isEqualTo("/tmp");
    }
}
