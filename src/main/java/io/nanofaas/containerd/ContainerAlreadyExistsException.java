package io.nanofaas.containerd;

/** A container with the requested id already exists in the configured namespace. */
public class ContainerAlreadyExistsException extends ContainerdException {

    /**
     * Creates the exception.
     *
     * @param message what failed
     */
    public ContainerAlreadyExistsException(String message) {
        super(message);
    }

    /**
     * Creates the exception, keeping the underlying failure as the cause.
     *
     * @param message what failed
     * @param cause the underlying failure, typically the gRPC status
     */
    public ContainerAlreadyExistsException(String message, Throwable cause) {
        super(message, cause);
    }
}
