package io.nanofaas.containerd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignalTest {

    @Test
    void carriesTheLinuxSignalNumbers() {
        assertThat(Signal.HUP.number()).isEqualTo(1);
        assertThat(Signal.KILL.number()).isEqualTo(9);
        assertThat(Signal.TERM.number()).isEqualTo(15);
        assertThat(Signal.STOP.number()).isEqualTo(19);
        assertThat(Signal.WINCH.number()).isEqualTo(28);
    }

    @Test
    void numbersAreUnique() {
        assertThat(java.util.Arrays.stream(Signal.values()).map(Signal::number).distinct().count())
                .isEqualTo(Signal.values().length);
    }

    @Test
    void fromNumberRoundTripsAndRejectsUnsupportedNumbers() {
        for (Signal signal : Signal.values()) {
            assertThat(Signal.fromNumber(signal.number())).isEqualTo(signal);
        }
        assertThatThrownBy(() -> Signal.fromNumber(64))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("64");
    }
}
