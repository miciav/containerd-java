package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.netty.channel.epoll.Epoll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcChannelFactoryTest {
    @Test
    void createsNettyChannel() {
        ManagedChannel channel = GrpcChannelFactory.createUnixDomainSocketChannel("/run/containerd/containerd.sock");
        try {
            assertThat(channel).isNotNull();
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    void requiresTheEpollNativeTransport() {
        // The factory refuses to build a channel without epoll, and the suite depends on it being
        // present. Asserting the precondition here turns "epoll is missing" into one clear
        // failure rather than a puzzling one inside Netty in every other test.
        //
        // The previous test here claimed to check the error message but had no assertion at all,
        // and could not have checked it: the message only appears when epoll is absent, which is
        // exactly when this suite cannot run.
        assertThat(Epoll.isAvailable())
                .as("netty epoll native transport must be on the test classpath")
                .isTrue();
    }
}
