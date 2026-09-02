package io.nanofaas.containerd;

/** Pulling an image from the configured registry failed. */
public class ImagePullException extends ContainerdException {

    public ImagePullException(String message) {
        super(message);
    }

    public ImagePullException(String message, Throwable cause) {
        super(message, cause);
    }
}
