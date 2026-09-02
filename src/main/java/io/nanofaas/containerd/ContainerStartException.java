package io.nanofaas.containerd;

/** Starting the container's task failed. */
public class ContainerStartException extends ContainerdException {

    public ContainerStartException(String message) {
        super(message);
    }

    public ContainerStartException(String message, Throwable cause) {
        super(message, cause);
    }
}
