package io.nanofaas.containerd.spi;

import io.nanofaas.containerd.Event;
import io.nanofaas.containerd.EventFilter;
import io.nanofaas.containerd.Subscription;

import java.util.function.Consumer;

/** containerd event stream. */
public interface Events {

    /**
     * Subscribes to events matching the filter. The handler is invoked on a virtual thread;
     * a slow handler never blocks stream delivery. The subscription reconnects with exponential
     * backoff (up to 30s) after stream errors until closed.
     *
     * @param filter which events to receive
     * @param handler called once per matching event
     * @return a handle that cancels the stream when closed
     */
    Subscription subscribe(EventFilter filter, Consumer<Event> handler);
}
