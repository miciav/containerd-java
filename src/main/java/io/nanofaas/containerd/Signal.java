package io.nanofaas.containerd;

/** POSIX signals, by number, as expected by containerd's Kill RPC. */
public enum Signal {
    HUP(1),
    INT(2),
    QUIT(3),
    TERM(15),
    KILL(9),
    USR1(10),
    USR2(12);

    private final int number;

    Signal(int number) {
        this.number = number;
    }

    public int number() {
        return number;
    }
}
