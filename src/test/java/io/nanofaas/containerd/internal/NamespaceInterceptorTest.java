package io.nanofaas.containerd.internal;

import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NamespaceInterceptorTest {

    private static final Metadata.Key<String> NS_KEY =
            Metadata.Key.of("containerd-namespace", Metadata.ASCII_STRING_MARSHALLER);

    @Test
    void addsNamespaceHeaderToEveryCall() throws Exception {
        AtomicReference<Metadata> captured = new AtomicReference<>();

        ServerServiceDefinition service = ServerInterceptors.intercept(
                containerd.services.version.v1.VersionGrpc.bindService(new containerd.services.version.v1.VersionGrpc.VersionImplBase() {
                    @Override
                    public void version(com.google.protobuf.Empty request,
                                        StreamObserver<containerd.services.version.v1.VersionResponse> responseObserver) {
                        responseObserver.onNext(containerd.services.version.v1.VersionResponse.getDefaultInstance());
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

        String name = InProcessServerBuilder.generateName();
        InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start();
        try {
            ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor()
                    .intercept(new NamespaceInterceptor("nanofaas"))
                    .build();
            var stub = containerd.services.version.v1.VersionGrpc.newBlockingStub(channel);
            stub.version(com.google.protobuf.Empty.getDefaultInstance());
            channel.shutdownNow();

            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().get(NS_KEY)).isEqualTo("nanofaas");
        } finally {
            InProcessServerBuilder.forName(name).build().shutdownNow();
        }
    }
}
