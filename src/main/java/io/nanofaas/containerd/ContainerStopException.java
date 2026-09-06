package io.nanofaas.containerd;

/** Stopping the container's task failed. */
public class ContainerStopException extends ContainerdException {

    /**
     * Creates the exception.
     *
     * @param message what failed
     */
    public ContainerStopException(String message) {
        super(message);
    }

    /**
     * Creates the exception, keeping the underlying failure as the cause.
     *
     * @param message what failed
     * @param cause the underlying failure, typically the gRPC status
     */
    public ContainerStopException(String message, Throwable cause) {
        super(message, cause);
    }
}
