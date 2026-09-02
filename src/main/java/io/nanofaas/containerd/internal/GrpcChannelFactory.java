package io.nanofaas.containerd.internal;

import io.grpc.ClientInterceptor;
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
        return createUnixDomainSocketChannel(socketPath, new ClientInterceptor[0]);
    }

    /**
     * Creates a channel with the given {@link ClientInterceptor}s attached at the channel level
     * (via {@link NettyChannelBuilder#intercept}), so every call made through the returned channel
     * passes through them.
     */
    public static ManagedChannel createUnixDomainSocketChannel(String socketPath,
                                                               ClientInterceptor... interceptors) {
        if (!Epoll.isAvailable()) {
            throw new IllegalStateException(
                    "Netty epoll native transport is not available; add netty-transport-native-epoll with the linux-x86_64 or linux-aarch_64 classifier");
        }
        NettyChannelBuilder builder = NettyChannelBuilder.forAddress(new DomainSocketAddress(socketPath))
                .channelType(EpollDomainSocketChannel.class)
                .eventLoopGroup(new EpollEventLoopGroup())
                .usePlaintext();
        if (interceptors != null && interceptors.length > 0) {
            builder.intercept(interceptors);
        }
        return builder.build();
    }
}
