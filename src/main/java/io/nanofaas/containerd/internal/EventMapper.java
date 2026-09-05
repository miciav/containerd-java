package io.nanofaas.containerd.internal;

import io.nanofaas.containerd.Event;
import io.nanofaas.containerd.TaskEvent;

import java.time.Instant;

/**
 * Decodes a containerd event envelope, extracting a typed {@link TaskEvent} where known.
 *
 * <p>The payload type is identified by the message name in the {@code google.protobuf.Any}
 * {@code type_url}. Real producers (containerd, via Go's protobuf {@code anypb}) emit the
 * canonical {@code type.googleapis.com/<package>.<Message>} form, so the scheme prefix is
 * stripped before comparing against the descriptor's fully-qualified name; a bare name is
 * accepted too. Comparing the raw {@code type_url} against {@code getFullName()} would never
 * match the real prefixed form, leaving every typed event as {@code null} in production.
 */
public final class EventMapper {

    private EventMapper() {
    }

    public static Event map(containerd.types.Envelope envelope) {
        TaskEvent taskEvent = null;
        String type = messageName(envelope.getEvent().getTypeUrl());
        try {
            if (type.equals(containerd.events.TaskStart.getDescriptor().getFullName())) {
                var p = containerd.events.TaskStart.parseFrom(envelope.getEvent().getValue());
                taskEvent = new TaskEvent(p.getContainerId(), p.getPid(), null);
            } else if (type.equals(containerd.events.TaskDelete.getDescriptor().getFullName())) {
                var p = containerd.events.TaskDelete.parseFrom(envelope.getEvent().getValue());
                taskEvent = new TaskEvent(p.getContainerId(), p.getPid(), p.getExitStatus());
            } else if (type.equals(containerd.events.TaskExit.getDescriptor().getFullName())) {
                var p = containerd.events.TaskExit.parseFrom(envelope.getEvent().getValue());
                taskEvent = new TaskEvent(p.getContainerId(), p.getPid(), p.getExitStatus());
            }
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            // malformed event payload: deliver without typed payload
        }
        return new Event(envelope.getTopic(), envelope.getNamespace(),
                envelope.hasTimestamp()
                        ? Instant.ofEpochSecond(envelope.getTimestamp().getSeconds(), envelope.getTimestamp().getNanos())
                        : null,
                taskEvent);
    }

    /** The {@code type_url} with any scheme prefix (e.g. {@code type.googleapis.com/}) removed. */
    private static String messageName(String typeUrl) {
        int slash = typeUrl.lastIndexOf('/');
        return slash >= 0 ? typeUrl.substring(slash + 1) : typeUrl;
    }
}
