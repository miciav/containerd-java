package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.nanofaas.containerd.ImagePullException;
import io.nanofaas.containerd.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pulls images through containerd's Transfer service (the mechanism ctr uses):
 * registry source + image-store destination with unpacking into the snapshotter.
 */
public final class TransferImagePuller {

    private static final Logger log = LoggerFactory.getLogger(TransferImagePuller.class);

    private final containerd.services.transfer.v1.TransferGrpc.TransferBlockingStub stub;
    private final String snapshotter;

    public TransferImagePuller(ManagedChannel channel, String snapshotter) {
        this.stub = containerd.services.transfer.v1.TransferGrpc.newBlockingStub(channel);
        this.snapshotter = snapshotter;
    }

    public void pull(String reference, Platform platform) {
        log.debug("pull start: reference={} platform={}/{} snapshotter={}",
                reference, platform.os(), platform.architecture(), snapshotter);

        var source = containerd.types.transfer.OCIRegistry.newBuilder()
                .setReference(reference)
                .build();
        var protoPlatform = ProtoMapper.toProto(platform);
        var destination = containerd.types.transfer.ImageStore.newBuilder()
                .setName(reference)
                .addPlatforms(protoPlatform)
                .setAllMetadata(true)
                .addUnpacks(containerd.types.transfer.UnpackConfiguration.newBuilder()
                        .setPlatform(protoPlatform)
                        .setSnapshotter(snapshotter))
                .build();

        var request = containerd.services.transfer.v1.TransferRequest.newBuilder()
                .setSource(TypeUrls.pack(source))
                .setDestination(TypeUrls.pack(destination))
                .build();

        try {
            // The Transfer RPC blocks until the transfer completes.
            stub.transfer(request);
        } catch (StatusRuntimeException e) {
            throw new ImagePullException("failed to pull image " + reference + ": " + e.getStatus(), e);
        }
        log.debug("pull complete: reference={}", reference);
    }
}
