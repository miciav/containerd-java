package io.nanofaas.containerd;

/** The requested container does not exist in the configured namespace. */
public class ContainerNotFoundException extends ContainerdException {

    public ContainerNotFoundException(String message) {
        super(message);
    }

    public ContainerNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
