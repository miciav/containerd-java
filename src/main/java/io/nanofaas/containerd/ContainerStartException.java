package io.nanofaas.containerd;

/** Starting the container's task failed. */
public class ContainerStartException extends ContainerdException {

    /**
     * Creates the exception.
     *
     * @param message what failed
     */
    public ContainerStartException(String message) {
        super(message);
    }

    /**
     * Creates the exception, keeping the underlying failure as the cause.
     *
     * @param message what failed
     * @param cause the underlying failure, typically the gRPC status
     */
    public ContainerStartException(String message, Throwable cause) {
        super(message, cause);
    }
}
