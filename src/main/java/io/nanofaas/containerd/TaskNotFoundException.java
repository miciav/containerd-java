package io.nanofaas.containerd;

/** The requested task does not exist in the configured namespace. */
public class TaskNotFoundException extends ContainerdException {

    public TaskNotFoundException(String message) {
        super(message);
    }

    public TaskNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
