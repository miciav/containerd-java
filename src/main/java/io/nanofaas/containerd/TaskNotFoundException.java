package io.nanofaas.containerd;

/** The requested task does not exist in the configured namespace. */
public class TaskNotFoundException extends ContainerdException {

    /**
     * Creates the exception.
     *
     * @param message what failed
     */
    public TaskNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates the exception, keeping the underlying failure as the cause.
     *
     * @param message what failed
     * @param cause the underlying failure, typically the gRPC status
     */
    public TaskNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
