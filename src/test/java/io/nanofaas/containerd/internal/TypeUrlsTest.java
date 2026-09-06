package io.nanofaas.containerd.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the wire format of outgoing Any type URLs. containerd dispatches on the exact string:
 * sending the canonical {@code type.googleapis.com/} form makes v2.2.1 answer
 * "UNIMPLEMENTED: method Transfer not implemented for ...", verified against a live daemon.
 * If this test ever looks wrong, read TypeUrls' javadoc before changing it.
 */
class TypeUrlsTest {

    @Test
    void packsWithTheBareMessageNameContainerdDispatchesOn() {
        var any = TypeUrls.pack(containerd.types.transfer.OCIRegistry.newBuilder()
                .setReference("docker.io/library/alpine:latest").build());

        assertThat(any.getTypeUrl()).isEqualTo("containerd.types.transfer.OCIRegistry");
        assertThat(any.getTypeUrl()).doesNotContain("type.googleapis.com");
    }

    @Test
    void preservesThePayload() throws Exception {
        var options = containerd.runc.v1.Options.newBuilder().setBinaryName("crun").build();

        var any = TypeUrls.pack(options);

        assertThat(any.getTypeUrl()).isEqualTo("containerd.runc.v1.Options");
        assertThat(containerd.runc.v1.Options.parseFrom(any.getValue()).getBinaryName()).isEqualTo("crun");
    }
}
