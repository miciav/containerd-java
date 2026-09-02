package io.nanofaas.containerd.internal;

import io.grpc.ManagedChannel;
import io.nanofaas.containerd.Version;
import io.nanofaas.containerd.spi.ContainerdClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DefaultContainerdClient implements ContainerdClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultContainerdClient.class);

    private final ManagedChannel channel;
    private final String namespace;
    private final String snapshotter;
    private final String runtimeName;
    private final String runtimeBinaryName;

    public DefaultContainerdClient(String socketPath, String namespace, String snapshotter,
                                   String runtimeName, String runtimeBinaryName) {
        this.namespace = namespace;
        this.snapshotter = snapshotter;
        this.runtimeName = runtimeName;
        this.runtimeBinaryName = runtimeBinaryName;
        // The NamespaceInterceptor is attached at the channel level, so EVERY call made through
        // this channel (version() and every facade built on channel()) carries the namespace header.
        this.channel = GrpcChannelFactory.createUnixDomainSocketChannel(socketPath,
                new NamespaceInterceptor(namespace));
        log.debug("containerd client created (namespace={}, snapshotter={}, runtime={}, binaryName={})",
                namespace, snapshotter, runtimeName, runtimeBinaryName);
    }

    @Override
    public Version version() {
        var response = containerd.services.version.v1.VersionGrpc.newBlockingStub(channel)
                .version(com.google.protobuf.Empty.getDefaultInstance());
        return new Version(response.getVersion(), response.getRevision());
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public String snapshotter() {
        return snapshotter;
    }

    @Override
    public String runtimeName() {
        return runtimeName;
    }

    String runtimeBinaryName() {
        return runtimeBinaryName;
    }

    ManagedChannel channel() {
        return channel;
    }

    @Override
    public void close() {
        log.debug("closing containerd client");
        channel.shutdownNow();
    }
}
