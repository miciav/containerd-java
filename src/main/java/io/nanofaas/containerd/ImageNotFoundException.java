package io.nanofaas.containerd;

/** The requested image does not exist in the configured namespace. */
public class ImageNotFoundException extends ContainerdException {

    public ImageNotFoundException(String message) {
        super(message);
    }

    public ImageNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
