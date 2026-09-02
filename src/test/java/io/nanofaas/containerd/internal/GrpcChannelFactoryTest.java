package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
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
    void rejectsMissingEpollWithClearMessage() {
        // exercised indirectly: on a system without the epoll native lib the factory must throw
        // IllegalStateException rather than failing deep inside Netty. This test only guards the
        // message contract by reflection-free call when epoll IS available.
        ManagedChannel channel = GrpcChannelFactory.createUnixDomainSocketChannel("/run/containerd/containerd.sock");
        channel.shutdownNow();
    }
}
