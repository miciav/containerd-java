package io.nanofaas.containerd;

import io.nanofaas.containerd.spi.ContainerdClient;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class ImagePullIT extends ContainerdConnectionIT {

    @Test
    @Timeout(300)
    void pullsListsGetsAndRemovesImage() {
        String ref = "docker.io/library/alpine:latest";
        String marker = "it-image-" + UUID.randomUUID();

        try {
            client.images().pull(ref);

            Image image = client.images().get(ref);
            assertThat(image.name()).isEqualTo(ref);
            assertThat(image.digest()).startsWith("sha256:");

            assertThat(client.images().list()).extracting(Image::name).contains(ref);

            // idempotent remove of a missing image must not throw
            client.images().remove(marker);
        } finally {
            client.images().remove(ref);
        }
    }

    @Test
    void getUnknownImageThrowsImageNotFound() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> client.images().get("it-unknown-" + UUID.randomUUID()))
                .isInstanceOf(ImageNotFoundException.class);
    }
}
