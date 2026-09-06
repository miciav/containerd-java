package io.nanofaas.containerd;

/** Pulling an image from the configured registry failed. */
public class ImagePullException extends ContainerdException {

    /**
     * Creates the exception.
     *
     * @param message what failed
     */
    public ImagePullException(String message) {
        super(message);
    }

    /**
     * Creates the exception, keeping the underlying failure as the cause.
     *
     * @param message what failed
     * @param cause the underlying failure, typically the gRPC status
     */
    public ImagePullException(String message, Throwable cause) {
        super(message, cause);
    }
}
