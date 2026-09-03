package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;

import java.io.ByteArrayOutputStream;

/** Reads blobs from containerd's content store. */
public final class ContentStoreReader {

    private final containerd.services.content.v1.ContentGrpc.ContentBlockingStub stub;

    public ContentStoreReader(ManagedChannel channel) {
        this.stub = containerd.services.content.v1.ContentGrpc.newBlockingStub(channel);
    }

    public byte[] read(String digest) {
        long size = stub.info(containerd.services.content.v1.InfoRequest.newBuilder()
                        .setDigest(digest).build())
                .getInfo().getSize();
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(size, 1 << 20));
        long offset = 0;
        while (offset < size) {
            // Client-streaming Read: the blocking stub returns an Iterator (not Iterable).
            var chunks = stub.read(containerd.services.content.v1.ReadContentRequest.newBuilder()
                    .setDigest(digest).setOffset(offset).setSize(size - offset).build());
            while (chunks.hasNext()) {
                var chunk = chunks.next();
                out.writeBytes(chunk.getData().toByteArray());
                offset += chunk.getData().size();
            }
        }
        return out.toByteArray();
    }
}
