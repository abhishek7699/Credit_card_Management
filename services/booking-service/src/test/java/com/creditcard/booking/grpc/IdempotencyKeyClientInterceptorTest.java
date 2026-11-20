package com.creditcard.booking.grpc;

import com.creditcard.transaction.grpc.v1.LookupTransactionByIdempotencyKeyRequest;
import com.creditcard.transaction.grpc.v1.LookupTransactionByIdempotencyKeyResponse;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyKeyClientInterceptorTest {

    private static final Metadata.Key<String> KEY_HEADER =
            Metadata.Key.of("idempotency-key", Metadata.ASCII_STRING_MARSHALLER);

    private final List<String> headersSeen = new CopyOnWriteArrayList<>();
    private Server server;
    private ManagedChannel managed;

    @AfterEach
    void tearDown() {
        managed.shutdownNow();
        server.shutdownNow();
    }

    private TransactionServiceGrpc.TransactionServiceBlockingStub stub() throws Exception {
        String name = "interceptor-test-" + UUID.randomUUID();
        ServerInterceptor record = new ServerInterceptor() {
            @Override
            public <Req, Resp> ServerCall.Listener<Req> interceptCall(ServerCall<Req, Resp> call, Metadata headers,
                                                                      ServerCallHandler<Req, Resp> next) {
                headersSeen.add(headers.get(KEY_HEADER));
                return next.startCall(call, headers);
            }
        };
        TransactionServiceGrpc.TransactionServiceImplBase impl = new TransactionServiceGrpc.TransactionServiceImplBase() {
            @Override
            public void lookupTransactionByIdempotencyKey(LookupTransactionByIdempotencyKeyRequest request,
                                                          StreamObserver<LookupTransactionByIdempotencyKeyResponse> ro) {
                ro.onNext(LookupTransactionByIdempotencyKeyResponse.getDefaultInstance());
                ro.onCompleted();
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(impl, record)).build().start();
        managed = InProcessChannelBuilder.forName(name).directExecutor().build();
        return TransactionServiceGrpc.newBlockingStub(ClientInterceptors.intercept(managed, new IdempotencyKeyClientInterceptor()));
    }

    @Test
    void callsOutsideWithKey_carryNoIdempotencyKey() throws Exception {
        TransactionServiceGrpc.TransactionServiceBlockingStub stub = stub();

        stub.lookupTransactionByIdempotencyKey(LookupTransactionByIdempotencyKeyRequest.newBuilder()
                .setIdempotencyKey("attempt-x").build());

        assertThat(headersSeen).containsExactly((String) null);
    }

    @Test
    void callsInsideWithKey_carryTheKey_andTheContextIsRestoredAfterwards() throws Exception {
        TransactionServiceGrpc.TransactionServiceBlockingStub stub = stub();

        String result = IdempotencyKeyClientInterceptor.withKey("attempt-42", () -> {
            stub.lookupTransactionByIdempotencyKey(LookupTransactionByIdempotencyKeyRequest.newBuilder()
                    .setIdempotencyKey("attempt-42").build());
            return "done";
        });
        stub.lookupTransactionByIdempotencyKey(LookupTransactionByIdempotencyKeyRequest.newBuilder()
                .setIdempotencyKey("attempt-42").build());

        assertThat(result).isEqualTo("done");
        assertThat(headersSeen).containsExactly("attempt-42", (String) null);
    }
}
