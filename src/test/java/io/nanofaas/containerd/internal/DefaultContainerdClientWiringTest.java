package io.nanofaas.containerd.internal;

import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.netty.channel.unix.DomainSocketAddress;
import io.nanofaas.containerd.Version;
import io.nanofaas.containerd.spi.ContainerdClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the R12 wiring end to end: a client built through the public API
 * (builder -&gt; DefaultContainerdClient -&gt; GrpcChannelFactory overload) carries the
 * namespace header on EVERY call. A real Netty epoll UDS server on a temp socket captures
 * the metadata of the Version RPC issued by {@link ContainerdClient#version()}; no
 * test-only seams, no /run/containerd access needed.
 */
class DefaultContainerdClientWiringTest {

    private static final Metadata.Key<String> NS_KEY =
            Metadata.Key.of("containerd-namespace", Metadata.ASCII_STRING_MARSHALLER);

    @Test
    void versionCallCarriesNamespaceHeaderOnRealUdsChannel(@TempDir Path tempDir) throws Exception {
        Path socket = tempDir.resolve("containerd.sock");
        AtomicReference<Metadata> captured = new AtomicReference<>();

        ServerServiceDefinition service = ServerInterceptors.intercept(
                containerd.services.version.v1.VersionGrpc.bindService(
                        new containerd.services.version.v1.VersionGrpc.VersionImplBase() {
                            @Override
                            public void version(com.google.protobuf.Empty request,
                                                StreamObserver<containerd.services.version.v1.VersionResponse> responseObserver) {
                                responseObserver.onNext(containerd.services.version.v1.VersionResponse.newBuilder()
                                        .setVersion("1.7.0")
                                        .setRevision("abc123")
                                        .build());
                                responseObserver.onCompleted();
                            }
                        }),
                new ServerInterceptor() {
                    @Override
                    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                                 Metadata headers,
                                                                                 ServerCallHandler<ReqT, RespT> next) {
                        captured.set(new Metadata());
                        captured.get().merge(headers);
                        return next.startCall(call, headers);
                    }
                });

        EpollEventLoopGroup boss = new EpollEventLoopGroup(1);
        EpollEventLoopGroup worker = new EpollEventLoopGroup(1);
        Server server = null;
        try {
            server = NettyServerBuilder.forAddress(new DomainSocketAddress(socket.toString()))
                    .channelType(EpollServerDomainSocketChannel.class)
                    .bossEventLoopGroup(boss)
                    .workerEventLoopGroup(worker)
                    .addService(service)
                    .build()
                    .start();

            try (ContainerdClient client = ContainerdClient.builder()
                    .socketPath(socket.toString())
                    .namespace("nanofaas-wiring")
                    .build()) {
                Version version = client.version();
                assertThat(version.version()).isEqualTo("1.7.0");
                assertThat(version.revision()).isEqualTo("abc123");
            }
        } finally {
            if (server != null) {
                server.shutdownNow();
            }
            boss.shutdownGracefully().syncUninterruptibly();
            worker.shutdownGracefully().syncUninterruptibly();
        }

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().get(NS_KEY)).isEqualTo("nanofaas-wiring");
    }
}
