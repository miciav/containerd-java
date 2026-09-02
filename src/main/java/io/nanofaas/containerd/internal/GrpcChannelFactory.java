package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;

/** Creates gRPC channels over Unix Domain Sockets using the Netty epoll transport. */
public final class GrpcChannelFactory {

    private GrpcChannelFactory() {
    }

    public static ManagedChannel createUnixDomainSocketChannel(String socketPath) {
        if (!Epoll.isAvailable()) {
            throw new IllegalStateException(
                    "Netty epoll native transport is not available; add netty-transport-native-epoll with the linux-x86_64 classifier");
        }
        return NettyChannelBuilder.forAddress(new DomainSocketAddress(socketPath))
                .channelType(EpollDomainSocketChannel.class)
                .eventLoopGroup(new EpollEventLoopGroup())
                .usePlaintext()
                .build();
    }
}
