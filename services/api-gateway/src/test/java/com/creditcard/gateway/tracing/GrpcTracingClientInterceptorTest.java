package com.creditcard.gateway.tracing;

import brave.Tracing;
import com.creditcard.transaction.grpc.v1.GetTransactionRequest;
import com.creditcard.transaction.grpc.v1.GetTransactionResponse;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcTracingClientInterceptorTest {

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    @Test
    void tracedCallReachesTheServerUnchanged() throws Exception {
        String name = "client-tracing-" + UUID.randomUUID();
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(new TransactionServiceGrpc.TransactionServiceImplBase() {
                    @Override
                    public void getTransaction(GetTransactionRequest request, StreamObserver<GetTransactionResponse> observer) {
                        observer.onNext(GetTransactionResponse.newBuilder().setTransactionId("tx-1").build());
                        observer.onCompleted();
                    }
                }).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        var intercepted = ClientInterceptors.intercept(channel, new GrpcTracingClientInterceptor(Tracing.newBuilder().build()));

        GetTransactionResponse response = TransactionServiceGrpc.newBlockingStub(intercepted)
                .getTransaction(GetTransactionRequest.newBuilder().setTransactionId("tx-1").build());

        assertThat(response.getTransactionId()).isEqualTo("tx-1");
    }
}
