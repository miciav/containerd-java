package io.nanofaas.containerd;

/** Executing a process in a container's task failed. */
public class ExecException extends ContainerdException {

    public ExecException(String message) {
        super(message);
    }

    public ExecException(String message, Throwable cause) {
        super(message, cause);
    }
}
