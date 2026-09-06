package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;

import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.List;

/** Resolves an image reference to the ChainID of its top layer (the snapshot parent key). */
public final class ImageRootfsResolver {

    private static final List<String> INDEX_MEDIA_TYPES = List.of(
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json");
    private static final List<String> MANIFEST_MEDIA_TYPES = List.of(
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json");

    private final containerd.services.images.v1.ImagesGrpc.ImagesBlockingStub images;
    private final ContentStoreReader content;

    public ImageRootfsResolver(ManagedChannel channel) {
        this.images = containerd.services.images.v1.ImagesGrpc.newBlockingStub(channel);
        this.content = new ContentStoreReader(channel);
    }

    public String resolveChainId(String imageName) {
        var image = images.get(containerd.services.images.v1.GetImageRequest.newBuilder()
                .setName(imageName).build()).getImage();

        var top = JsonSupport.parse(json(content.read(image.getTarget().getDigest())));
        String mediaType = field(top, "mediaType");
        if (INDEX_MEDIA_TYPES.contains(mediaType)) {
            // pick the first platform entry matching the host platform, else the first entry
            String manifestDigest = pickPlatformManifest(top);
            top = JsonSupport.parse(json(content.read(manifestDigest)));
            mediaType = field(top, "mediaType");
        }
        if (!MANIFEST_MEDIA_TYPES.contains(mediaType)) {
            throw new IllegalStateException("unsupported manifest media type: " + mediaType);
        }

        String configDigest = top.getFieldsOrThrow("config").getStructValue()
                .getFieldsOrThrow("digest").getStringValue();
        var config = JsonSupport.parse(json(content.read(configDigest)));
        var diffIds = config.getFieldsOrThrow("rootfs").getStructValue()
                .getFieldsOrThrow("diff_ids").getListValue().getValuesList();

        List<String> ids = new ArrayList<>();
        for (var v : diffIds) {
            ids.add(v.getStringValue());
        }
        return ChainIds.chainId(ids);
    }

    private static String pickPlatformManifest(com.google.protobuf.Struct index) {
        var manifests = index.getFieldsOrThrow("manifests").getListValue().getValuesList();
        if (manifests.isEmpty()) {
            throw new IllegalStateException("image index has no manifests");
        }
        var host = io.nanofaas.containerd.Platform.host();
        for (var m : manifests) {
            var platform = m.getStructValue().getFieldsOrDefault("platform",
                    com.google.protobuf.Value.getDefaultInstance()).getStructValue();
            if (platform.getFieldsOrDefault("os", com.google.protobuf.Value.getDefaultInstance()).getStringValue().equals(host.os())
                    && platform.getFieldsOrDefault("architecture", com.google.protobuf.Value.getDefaultInstance()).getStringValue().equals(host.architecture())) {
                return m.getStructValue().getFieldsOrThrow("digest").getStringValue();
            }
        }
        return manifests.get(0).getStructValue().getFieldsOrThrow("digest").getStringValue();
    }

    /** OCI manifests and configs are UTF-8 by specification, never the platform default. */
    private static String json(byte[] blob) {
        return new String(blob, StandardCharsets.UTF_8);
    }

    private static String field(com.google.protobuf.Struct struct, String name) {
        return struct.getFieldsOrDefault(name, com.google.protobuf.Value.getDefaultInstance()).getStringValue();
    }
}
