package io.nanofaas.containerd;

/**
 * POSIX signals, by number, as expected by containerd's Kill RPC.
 *
 * <p>The numbers are the Linux ones for the common architectures (x86-64, arm64). They differ on
 * some other platforms, which does not arise here: containerd tasks are Linux processes.
 */
public enum Signal {
    /** Hangup (1). Conventionally asks a daemon to reload its configuration. */
    HUP(1),
    /** Interrupt (2), what Ctrl-C sends. */
    INT(2),
    /** Quit (3). Terminates and dumps core. */
    QUIT(3),
    /** Illegal instruction (4). */
    ILL(4),
    /** Abort (6), as raised by {@code abort()}. */
    ABRT(6),
    /** Floating-point exception (8). */
    FPE(8),
    /** Kill (9). Cannot be caught, blocked or ignored. */
    KILL(9),
    /** User-defined signal 1 (10). */
    USR1(10),
    /** Invalid memory reference (11). */
    SEGV(11),
    /** User-defined signal 2 (12). */
    USR2(12),
    /** Broken pipe (13). */
    PIPE(13),
    /** Timer expired (14). */
    ALRM(14),
    /** Termination request (15). The polite stop, and what {@code Containers.stop} sends first. */
    TERM(15),
    /** Child stopped or terminated (17). */
    CHLD(17),
    /** Continue if stopped (18). */
    CONT(18),
    /** Stop the process (19). Cannot be caught or ignored. */
    STOP(19),
    /** Stop typed at the terminal (20). */
    TSTP(20),
    /** Terminal window size changed (28). */
    WINCH(28);

    private final int number;

    Signal(int number) {
        this.number = number;
    }

    /** {@return the signal number to put on the wire} */
    public int number() {
        return number;
    }

    /**
     * Looks a signal up by its number.
     *
     * @param number POSIX signal number
     * @return the matching constant
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
