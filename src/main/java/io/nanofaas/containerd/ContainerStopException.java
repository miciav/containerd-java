package io.nanofaas.containerd;

/** Stopping the container's task failed. */
public class ContainerStopException extends ContainerdException {

    public ContainerStopException(String message) {
        super(message);
    }

    public ContainerStopException(String message, Throwable cause) {
        super(message, cause);
    }
}
