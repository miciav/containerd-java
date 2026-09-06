package io.nanofaas.containerd;

/** Executing a process in a container's task failed. */
public class ExecException extends ContainerdException {

    /**
     * Creates the exception.
     *
     * @param message what failed
     */
    public ExecException(String message) {
        super(message);
    }

    /**
     * Creates the exception, keeping the underlying failure as the cause.
     *
     * @param message what failed
     * @param cause the underlying failure, typically the gRPC status
     */
    public ExecException(String message, Throwable cause) {
        super(message, cause);
    }
}
