package io.nanofaas.containerd.internal;

import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * containerd leases: a temporary owner for resources that exist before the thing that will own
 * them does.
 *
 * <p>A container's snapshot is prepared before the container record exists, so in between it is
 * referenced by nothing. An in-process failure can be cleaned up — {@code ContainersServiceImpl}
 * does — but a process killed in that window leaves a snapshot behind with no owner and no record
 * of what it was for. A lease closes that: containerd itself holds the reference, and the lease
 * carries a {@code containerd.io/gc.expire} of 24 hours, so an abandoned snapshot becomes
 * collectable instead of staying forever.
 *
 * <p>The lease travels as the {@code containerd-lease} gRPC header on the calls it should cover,
 * which is why it is applied per call rather than on the channel the way the namespace is.
 */
final class LeaseManager {

    private static final Logger log = LoggerFactory.getLogger(LeaseManager.class);

    static final Metadata.Key<String> LEASE_KEY =
            Metadata.Key.of("containerd-lease", Metadata.ASCII_STRING_MARSHALLER);

    private final containerd.services.leases.v1.LeasesGrpc.LeasesBlockingStub stub;

    LeaseManager(ManagedChannel channel) {
        this.stub = containerd.services.leases.v1.LeasesGrpc.newBlockingStub(channel);
    }

    /**
     * Creates a lease, or returns null if this containerd will not give one.
     *
     * <p>Null rather than throwing: a lease makes an abandoned resource collectable, it is not
     * what makes the operation correct. Failing a container create because the lease service
     * refused would trade a rare cleanup problem for an outright outage.
     *
     * @param id identifier for the lease, so an abandoned one says what it was for
     * @return the lease, or null if it could not be created
     */
    Lease create(String id) {
        try {
            var lease = stub.create(containerd.services.leases.v1.CreateRequest.newBuilder()
                    .setId(id)
                    .build()).getLease();
            if (log.isDebugEnabled()) {
                // The label lookup is a method call, not a value: without the guard it runs on
                // every create whether or not anything is listening.
                log.debug("lease created: id={} expires={}", lease.getId(),
                        lease.getLabelsMap().get("containerd.io/gc.expire"));
            }
            return new Lease(lease.getId());
        } catch (StatusRuntimeException e) {
            log.warn("could not create a lease ({}); proceeding without one, so a snapshot"
                    + " abandoned by a crash in the next moments would not be collected",
                    e.getStatus().getCode());
            return null;
        }
    }

    /**
     * Releases a lease. Once the container exists it references the snapshot itself, so holding
     * the lease any longer would only keep resources alive that should be free to go.
     *
     * @param lease the lease to release; null is ignored
     */
    void release(Lease lease) {
        if (lease == null) {
            return;
        }
        try {
            stub.delete(containerd.services.leases.v1.DeleteRequest.newBuilder()
                    .setId(lease.id())
                    .setSync(false)
                    .build());
            log.debug("lease released: id={}", lease.id());
        } catch (StatusRuntimeException e) {
            // It expires on its own; a failure here delays cleanup rather than breaking anything.
            log.warn("could not release lease {}: {}", lease.id(), e.getStatus().getCode());
        }
    }

    /**
     * A held lease.
     *
     * @param id the lease id, as containerd assigned it
     */
    record Lease(String id) {

        /** An interceptor that puts this lease on the calls it should cover. */
        ClientInterceptor asHeader() {
            Metadata metadata = new Metadata();
            metadata.put(LEASE_KEY, id);
            return MetadataUtils.newAttachHeadersInterceptor(metadata);
        }
    }
}
