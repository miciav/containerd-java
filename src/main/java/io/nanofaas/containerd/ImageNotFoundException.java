package io.nanofaas.containerd;

/** The requested image does not exist in the configured namespace. */
public class ImageNotFoundException extends ContainerdException {

    /**
     * Creates the exception.
     *
     * @param message what failed
     */
    public ImageNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates the exception, keeping the underlying failure as the cause.
     *
     * @param message what failed
     * @param cause the underlying failure, typically the gRPC status
     */
    public ImageNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
