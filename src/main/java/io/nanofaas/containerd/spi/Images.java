package io.nanofaas.containerd.spi;

import io.nanofaas.containerd.Image;
import io.nanofaas.containerd.Platform;

import java.util.List;

/** Operations on containerd images. */
public interface Images {

    /**
     * Pulls an image for the host platform, unpacking it into the configured snapshotter.
     *
     * @param reference image reference, e.g. {@code docker.io/library/alpine:latest}
     */
    void pull(String reference);

    /**
     * Pulls an image for a specific platform.
     *
     * @param reference image reference
     * @param platform platform to fetch and unpack
     */
    void pull(String reference, Platform platform);

    /**
     * Looks an image up in the store.
     *
     * @param name image reference
     * @return the stored image
     * @throws io.nanofaas.containerd.ImageNotFoundException if it is not in the store
     */
    Image get(String name);

    /** {@return every image in the client's namespace} */
    List<Image> list();

    /**
     * Removes an image. Idempotent — a missing image is ignored.
     *
     * @param name image reference
     */
    void remove(String name);
}
