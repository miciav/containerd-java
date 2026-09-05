package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.nanofaas.containerd.Event;
import io.nanofaas.containerd.EventFilter;
import io.nanofaas.containerd.Subscription;
import io.nanofaas.containerd.spi.Events;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * containerd event stream. Subscriptions run the user handler on a virtual thread (a slow
 * handler never blocks the gRPC callback thread) and reconnect with exponential backoff
 * (1s, doubling, capped at 30s) after a stream error until the subscription is closed.
 */
public final class EventsServiceImpl implements Events {

    private static final Logger log = LoggerFactory.getLogger(EventsServiceImpl.class);
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 30_000;

    private final containerd.services.events.v1.EventsGrpc.EventsStub stub;
    private final String namespace;
    private final java.util.concurrent.ExecutorService handlerExecutor =
            Executors.newVirtualThreadPerTaskExecutor();
    private final java.util.concurrent.ScheduledExecutorService reconnectScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "containerd-events-reconnect");
                t.setDaemon(true);
                return t;
            });

    public EventsServiceImpl(ManagedChannel channel, String namespace) {
        this.stub = containerd.services.events.v1.EventsGrpc.newStub(channel);
        this.namespace = namespace;
    }

    @Override
    public Subscription subscribe(EventFilter filter, Consumer<Event> handler) {
        var closed = new AtomicBoolean(false);
        var backoff = new AtomicLong(INITIAL_BACKOFF_MS);
        log.debug("events subscribe: filters={}", filter.toFieldpathFilters(namespace));
        connect(filter, handler, closed, backoff);
        return () -> {
            closed.set(true);
            log.debug("events subscription closed");
        };
    }

    private void connect(EventFilter filter, Consumer<Event> handler,
                         AtomicBoolean closed, AtomicLong backoff) {
        if (closed.get()) {
            return;
        }
        var request = containerd.services.events.v1.SubscribeRequest.newBuilder()
                .addAllFilters(filter.toFieldpathFilters(namespace))
                .build();
        stub.subscribe(request, new StreamObserver<containerd.types.Envelope>() {
            @Override
            public void onNext(containerd.types.Envelope envelope) {
                if (closed.get()) {
                    return;
                }
                backoff.set(INITIAL_BACKOFF_MS); // the stream is healthy: reset the backoff
                // The stream is scoped to the namespace server-side; select topics client-side,
                // because this containerd's fieldpath parser rejects multi-filter combinations.
                if (!matches(filter, envelope.getTopic())) {
                    return;
                }
                Event event = EventMapper.map(envelope);
                handlerExecutor.submit(() -> {
                    try {
                        handler.accept(event);
                    } catch (Exception e) {
                        log.warn("event handler threw for topic {}", event.topic(), e);
                    }
                });
            }

            @Override
            public void onError(Throwable t) {
                handleStreamEnd(t, filter, handler, closed, backoff);
            }

            @Override
            public void onCompleted() {
                handleStreamEnd(null, filter, handler, closed, backoff);
            }
        });
    }

    /** Client-side topic selection: an empty topic list matches every topic. */
    private static boolean matches(EventFilter filter, String topic) {
        List<String> topics = filter.topics();
        return topics.isEmpty() || topics.contains(topic);
    }

    private void handleStreamEnd(Throwable error, EventFilter filter, Consumer<Event> handler,
                                 AtomicBoolean closed, AtomicLong backoff) {
        if (closed.get()) {
            return;
        }
        if (error instanceof StatusRuntimeException sre
                && sre.getStatus().getCode() == io.grpc.Status.Code.UNIMPLEMENTED) {
            log.error("events not supported by this containerd; giving up");
            return;
        }
        long delay = backoff.getAndSet(Math.min(backoff.get() * 2, MAX_BACKOFF_MS));
        if (error != null) {
            log.warn("events stream error (reconnecting in {}ms): {}", delay, error.getMessage());
        } else {
            log.warn("events stream ended (reconnecting in {}ms)", delay);
        }
        scheduleReconnect(delay, () -> connect(filter, handler, closed, backoff));
    }

    /** Schedules a one-shot reconnect on the shared daemon scheduler. */
    private void scheduleReconnect(long delayMs, Runnable reconnect) {
        reconnectScheduler.schedule(reconnect, delayMs, TimeUnit.MILLISECONDS);
    }
}
