package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Prepares/removes snapshots for container root filesystems. The snapshotter is supplied per
 * call, so the same instance serves both a client's default snapshotter and a per-container one.
 */
public final class SnapshotManager {

    private static final Logger log = LoggerFactory.getLogger(SnapshotManager.class);

    private final containerd.services.snapshots.v1.SnapshotsGrpc.SnapshotsBlockingStub stub;

    public SnapshotManager(ManagedChannel channel) {
        this.stub = containerd.services.snapshots.v1.SnapshotsGrpc.newBlockingStub(channel);
    }

    /** Prepares an active snapshot keyed by {@code key}, parented on {@code parent} (may be empty). */
    public List<containerd.types.Mount> prepare(String snapshotter, String key, String parent) {
        log.debug("snapshot prepare: snapshotter={} key={} parent={}", snapshotter, key, parent);
        var response = stub.prepare(containerd.services.snapshots.v1.PrepareSnapshotRequest.newBuilder()
                .setSnapshotter(snapshotter)
                .setKey(key)
                .setParent(parent)
                .build());
        return List.copyOf(response.getMountsList());
    }

    /** Returns the mounts for an existing snapshot key. */
    public List<containerd.types.Mount> mounts(String snapshotter, String key) {
        try {
            return List.copyOf(stub.mounts(containerd.services.snapshots.v1.MountsRequest.newBuilder()
                    .setSnapshotter(snapshotter)
                    .setKey(key)
                    .build()).getMountsList());
        } catch (StatusRuntimeException e) {
            throw StatusExceptionMapper.map(e, StatusExceptionMapper.ResourceKind.SNAPSHOT);
        }
    }

    /** Removes a snapshot; idempotent — a missing snapshot is logged at DEBUG and ignored. */
    public void remove(String snapshotter, String key) {
        log.debug("snapshot remove: snapshotter={} key={}", snapshotter, key);
        try {
            stub.remove(containerd.services.snapshots.v1.RemoveSnapshotRequest.newBuilder()
                    .setSnapshotter(snapshotter)
                    .setKey(key)
                    .build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                log.debug("snapshot {} already gone (idempotent remove)", key);
                return;
            }
            throw e;
        }
    }
}
