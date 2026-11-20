package com.creditcard.transaction.api.grpc;

import com.creditcard.transaction.domain.Transaction;
import com.creditcard.transaction.domain.TransactionStatus;
import com.creditcard.transaction.grpc.v1.LookupTransactionByIdempotencyKeyRequest;
import com.creditcard.transaction.grpc.v1.LookupTransactionByIdempotencyKeyResponse;
import com.creditcard.transaction.service.TransactionService;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LookupTransactionByIdempotencyKeyGrpcTest {

    @Mock
    private TransactionService transactionService;

    @Mock
    private StreamObserver<LookupTransactionByIdempotencyKeyResponse> responseObserver;

    private TransactionGrpcService grpcService;

    @BeforeEach
    void setUp() {
        grpcService = new TransactionGrpcService(transactionService);
    }

    @Test
    void reportsTheCurrentStatusOfTheTransactionCreatedUnderTheKey() {
        UUID transactionId = UUID.randomUUID();
        Transaction transaction = mock(Transaction.class);
        when(transaction.getId()).thenReturn(transactionId);
        when(transaction.getStatus()).thenReturn(TransactionStatus.AUTHORIZED);
        when(transactionService.findByIdempotencyKey("attempt-1")).thenReturn(Optional.of(transaction));

        grpcService.lookupTransactionByIdempotencyKey(request("attempt-1"), responseObserver);

        ArgumentCaptor<LookupTransactionByIdempotencyKeyResponse> captor =
                ArgumentCaptor.forClass(LookupTransactionByIdempotencyKeyResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();
        assertThat(captor.getValue().getFound()).isTrue();
        assertThat(captor.getValue().getTransactionId()).isEqualTo(transactionId.toString());
        assertThat(captor.getValue().getStatus()).isEqualTo("AUTHORIZED");
    }

    @Test
    void reportsNotFoundWhenNothingWasCreatedUnderTheKey() {
        when(transactionService.findByIdempotencyKey("never-used")).thenReturn(Optional.empty());

        grpcService.lookupTransactionByIdempotencyKey(request("never-used"), responseObserver);

        ArgumentCaptor<LookupTransactionByIdempotencyKeyResponse> captor =
                ArgumentCaptor.forClass(LookupTransactionByIdempotencyKeyResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();
        assertThat(captor.getValue().getFound()).isFalse();
        assertThat(captor.getValue().getTransactionId()).isEmpty();
        assertThat(captor.getValue().getStatus()).isEmpty();
    }

    @Test
    void rejectsABlankKey() {
        grpcService.lookupTransactionByIdempotencyKey(request("  "), responseObserver);

        assertRejectedWith(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void rejectsAKeyLongerThanTheColumnAllows() {
        grpcService.lookupTransactionByIdempotencyKey(
                request("x".repeat(TransactionGrpcService.MAX_IDEMPOTENCY_KEY_LENGTH + 1)), responseObserver);

        assertRejectedWith(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void reportsInternalWhenTheLookupFails() {
        when(transactionService.findByIdempotencyKey(anyString())).thenThrow(new IllegalStateException("db down"));

        grpcService.lookupTransactionByIdempotencyKey(request("attempt-1"), responseObserver);

        assertRejectedWith(Status.Code.INTERNAL);
    }

    private static LookupTransactionByIdempotencyKeyRequest request(String key) {
        return LookupTransactionByIdempotencyKeyRequest.newBuilder().setIdempotencyKey(key).build();
    }

    private void assertRejectedWith(Status.Code code) {
        ArgumentCaptor<StatusRuntimeException> captor = ArgumentCaptor.forClass(StatusRuntimeException.class);
        verify(responseObserver).onError(captor.capture());
        assertThat(captor.getValue().getStatus().getCode()).isEqualTo(code);
    }
}
