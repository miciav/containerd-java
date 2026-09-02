package io.nanofaas.containerd;

/** A container with the requested id already exists in the configured namespace. */
public class ContainerAlreadyExistsException extends ContainerdException {

    public ContainerAlreadyExistsException(String message) {
        super(message);
    }

    public ContainerAlreadyExistsException(String message, Throwable cause) {
        super(message, cause);
    }
}
