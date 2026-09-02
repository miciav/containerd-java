package io.nanofaas.containerd.internal;

import io.grpc.*;

import java.util.Objects;

/** Injects the containerd namespace header into every outgoing call. */
public final class NamespaceInterceptor implements ClientInterceptor {

    static final Metadata.Key<String> NAMESPACE_KEY =
            Metadata.Key.of("containerd-namespace", Metadata.ASCII_STRING_MARSHALLER);

    private final String namespace;

    public NamespaceInterceptor(String namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                               CallOptions callOptions,
                                                               Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(ClientCall.Listener<RespT> responseListener, Metadata headers) {
                headers.put(NAMESPACE_KEY, namespace);
                super.start(responseListener, headers);
            }
        };
    }
}
