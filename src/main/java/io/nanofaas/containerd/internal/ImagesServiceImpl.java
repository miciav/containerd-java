package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.nanofaas.containerd.Image;
import io.nanofaas.containerd.Platform;
import io.nanofaas.containerd.spi.Images;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class ImagesServiceImpl implements Images {

    private static final Logger log = LoggerFactory.getLogger(ImagesServiceImpl.class);

    private final containerd.services.images.v1.ImagesGrpc.ImagesBlockingStub stub;
    private final TransferImagePuller puller;

    public ImagesServiceImpl(ManagedChannel channel, String snapshotter) {
        this.stub = containerd.services.images.v1.ImagesGrpc.newBlockingStub(channel);
        this.puller = new TransferImagePuller(channel, snapshotter);
    }

    @Override
    public void pull(String reference) {
        pull(reference, Platform.linuxAmd64());
    }

    @Override
    public void pull(String reference, Platform platform) {
        puller.pull(reference, platform);
    }

    @Override
    public Image get(String name) {
        try {
            var response = stub.get(containerd.services.images.v1.GetImageRequest.newBuilder()
                    .setName(name).build());
            return ProtoMapper.map(response.getImage());
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.IMAGE);
        }
    }

    @Override
    public List<Image> list() {
        return stub.list(containerd.services.images.v1.ListImagesRequest.getDefaultInstance())
                .getImagesList().stream()
                .map(ProtoMapper::map)
                .toList();
    }

    @Override
    public void remove(String name) {
        log.debug("image remove: name={}", name);
        try {
            stub.delete(containerd.services.images.v1.DeleteImageRequest.newBuilder()
                    .setName(name)
                    .setSync(true)
                    .build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                log.debug("image {} already gone (idempotent remove)", name);
                return;
            }
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.IMAGE);
        }
    }
}
