package io.nanofaas.containerd;

/** Base class for all exceptions thrown by this library. */
public class ContainerdException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message what failed
     */
    public ContainerdException(String message) {
        super(message);
    }

    /**
     * Creates the exception, keeping the underlying failure as the cause.
     *
     * @param message what failed
     * @param cause the underlying failure, typically the gRPC status
     */
    public ContainerdException(String message, Throwable cause) {
        super(message, cause);
    }
}
