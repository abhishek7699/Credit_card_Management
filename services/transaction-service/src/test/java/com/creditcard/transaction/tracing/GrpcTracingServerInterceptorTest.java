package com.creditcard.transaction.tracing;

import brave.Tracing;
import com.creditcard.transaction.grpc.v1.GetTransactionRequest;
import com.creditcard.transaction.grpc.v1.GetTransactionResponse;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcTracingServerInterceptorTest {

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    @Test
    void tracedServerHandlesCallsUnchanged() throws Exception {
        String name = "server-tracing-" + UUID.randomUUID();
        var service = new TransactionServiceGrpc.TransactionServiceImplBase() {
            @Override
            public void getTransaction(GetTransactionRequest request, StreamObserver<GetTransactionResponse> observer) {
                observer.onNext(GetTransactionResponse.newBuilder().setTransactionId("tx-2").build());
                observer.onCompleted();
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(service,
                        new GrpcTracingServerInterceptor(Tracing.newBuilder().build())))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        GetTransactionResponse response = TransactionServiceGrpc.newBlockingStub(channel)
                .getTransaction(GetTransactionRequest.newBuilder().setTransactionId("tx-2").build());

        assertThat(response.getTransactionId()).isEqualTo("tx-2");
    }
}
