package io.nanofaas.containerd.internal;

import com.google.protobuf.Any;
import com.google.protobuf.Message;

/**
 * Builds the {@code google.protobuf.Any} type URLs containerd expects for protobuf payloads
 * this client SENDS: the bare fully-qualified message name, with no scheme prefix.
 *
 * <p>This is deliberate, and it is not the canonical anypb form. containerd dispatches on the
 * exact {@code type_url} string: its typeurl registry is keyed by bare name, and the Transfer
 * service looks its source/destination handlers up by that key. Sending the canonical
 * {@code type.googleapis.com/...} form makes containerd v2.2.1 reject the call outright —
 * verified against a live daemon:
 *
 * <pre>
 * UNIMPLEMENTED: method Transfer not implemented for
 *   type.googleapis.com/containerd.types.transfer.OCIRegistry
 *   to type.googleapis.com/containerd.types.transfer.ImageStore
 * </pre>
 *
 * <p>The asymmetry with {@link EventMapper} is real, not an oversight: events come FROM
 * containerd through Go's {@code anypb}, which does emit the canonical prefixed form, so the
 * decode path has to strip a scheme that the encode path must never add.
 *
 * <p>OCI runtime specs do not go through here at all: those travel as JSON under containerd's
 * own {@code types.containerd.io/...} URLs, which {@link OciSpecBuilder} spells out.
 */
final class TypeUrls {

    private TypeUrls() {
    }

    /** Wraps {@code message} in an {@link Any} carrying the type URL containerd dispatches on. */
    static Any pack(Message message) {
        return Any.newBuilder()
                .setTypeUrl(message.getDescriptorForType().getFullName())
                .setValue(message.toByteString())
                .build();
    }
}
