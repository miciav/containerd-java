package io.nanofaas.containerd;

/** The requested container does not exist in the configured namespace. */
public class ContainerNotFoundException extends ContainerdException {

    /**
     * Creates the exception.
     *
     * @param message what failed
     */
    public ContainerNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates the exception, keeping the underlying failure as the cause.
     *
     * @param message what failed
     * @param cause the underlying failure, typically the gRPC status
     */
    public ContainerNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
