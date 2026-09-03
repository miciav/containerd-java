package io.nanofaas.containerd.spi;

import io.nanofaas.containerd.Image;
import io.nanofaas.containerd.Platform;

import java.util.List;

/** Operations on containerd images. */
public interface Images {

    /** Pulls an image from its registry into containerd (unpacking into the configured snapshotter). */
    void pull(String reference);

    /** Pulls an image for a specific platform. */
    void pull(String reference, Platform platform);

    Image get(String name);

    List<Image> list();

    /** Removes an image; idempotent (a missing image is ignored). */
    void remove(String name);
}
