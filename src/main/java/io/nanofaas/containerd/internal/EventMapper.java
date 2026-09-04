package io.nanofaas.containerd.internal;

import io.nanofaas.containerd.Event;
import io.nanofaas.containerd.TaskEvent;

import java.time.Instant;

/** Decodes a containerd event envelope, extracting a typed {@link TaskEvent} where known. */
public final class EventMapper {

    private EventMapper() {
    }

    public static Event map(containerd.types.Envelope envelope) {
        TaskEvent taskEvent = null;
        try {
            String typeUrl = envelope.getEvent().getTypeUrl();
            if (typeUrl.equals(containerd.events.TaskStart.getDescriptor().getFullName())) {
                var p = containerd.events.TaskStart.parseFrom(envelope.getEvent().getValue());
                taskEvent = new TaskEvent(p.getContainerId(), p.getPid(), null);
            } else if (typeUrl.equals(containerd.events.TaskDelete.getDescriptor().getFullName())) {
                var p = containerd.events.TaskDelete.parseFrom(envelope.getEvent().getValue());
                taskEvent = new TaskEvent(p.getContainerId(), p.getPid(), p.getExitStatus());
            } else if (typeUrl.equals(containerd.events.TaskExit.getDescriptor().getFullName())) {
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
}
