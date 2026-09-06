package io.nanofaas.containerd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentifiersTest {

    @Test
    void acceptsWhatContainerdAccepts() {
        assertThat(Identifiers.requireValid("abc_1.2-3")).isEqualTo("abc_1.2-3");
        assertThat(Identifiers.requireValid("a")).isEqualTo("a");
        assertThat(Identifiers.requireValid("nanofaas-fn-01")).isEqualTo("nanofaas-fn-01");
        assertThat(Identifiers.requireValid("a".repeat(76))).hasSize(76);
    }

    @Test
    void rejectsLeadingTrailingAndDoubledSeparators() {
        // The previous pattern let these through, so they failed server-side a round trip later.
        assertThatThrownBy(() -> Identifiers.requireValid("abc-"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("abc-");
        assertThatThrownBy(() -> Identifiers.requireValid("-abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Identifiers.requireValid("a..b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Identifiers.requireValid("a__b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyNullSpacedAndOverlongIds() {
        assertThatThrownBy(() -> Identifiers.requireValid("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Identifiers.requireValid(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Identifiers.requireValid("has space")).isInstanceOf(IllegalArgumentException.class);
        String tooLong = "a".repeat(77);
        assertThatThrownBy(() -> Identifiers.requireValid(tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
