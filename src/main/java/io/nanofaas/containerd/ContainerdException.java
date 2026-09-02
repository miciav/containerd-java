package io.nanofaas.containerd;

/** Base class for all exceptions thrown by this library. */
public class ContainerdException extends RuntimeException {

    public ContainerdException(String message) {
        super(message);
    }

    public ContainerdException(String message, Throwable cause) {
        super(message, cause);
    }
}
