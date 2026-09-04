package io.nanofaas.containerd;

/** A live event subscription; close it to stop receiving events. */
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}
