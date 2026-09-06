package io.nanofaas.containerd;

/** POSIX signals, by number, as expected by containerd's Kill RPC (Linux/x86-64 numbering). */
public enum Signal {
    HUP(1),
    INT(2),
    QUIT(3),
    ILL(4),
    ABRT(6),
    FPE(8),
    KILL(9),
    USR1(10),
    SEGV(11),
    USR2(12),
    PIPE(13),
    ALRM(14),
    TERM(15),
    CHLD(17),
    CONT(18),
    STOP(19),
    TSTP(20),
    WINCH(28);

    private final int number;

    Signal(int number) {
        this.number = number;
    }

    public int number() {
        return number;
    }

    /**
     * The signal with the given number.
     *
     * @throws IllegalArgumentException if no constant carries that number
     */
    public static Signal fromNumber(int number) {
        for (Signal signal : values()) {
            if (signal.number == number) {
                return signal;
            }
        }
        throw new IllegalArgumentException("unsupported signal number: " + number);
    }
}
