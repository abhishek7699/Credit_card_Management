package com.creditcard.transaction.api.grpc;

import com.creditcard.transaction.domain.Transaction;
import com.creditcard.transaction.domain.TransactionStatus;
import com.creditcard.transaction.grpc.v1.GetTransactionRequest;
import com.creditcard.transaction.grpc.v1.GetTransactionResponse;
import com.creditcard.transaction.grpc.v1.InitiateTransactionRequest;
import com.creditcard.transaction.grpc.v1.InitiateTransactionResponse;
import com.creditcard.transaction.service.TransactionService;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionGrpcServiceTest {

    @Mock
    private TransactionService transactionService;

    @Mock
    private StreamObserver<InitiateTransactionResponse> responseObserver;

    @Mock
    private StreamObserver<GetTransactionResponse> getResponseObserver;

    private TransactionGrpcService transactionGrpcService;

    @BeforeEach
    void setUp() {
        transactionGrpcService = new TransactionGrpcService(transactionService);
    }

    @Test
    void shouldReturnTransactionIdAndPendingStatusForValidRequest() {
        UUID txId = UUID.randomUUID();
        when(transactionService.initiateTransaction(any(), any(), any(), any(), any())).thenReturn(txId);

        InitiateTransactionRequest request = InitiateTransactionRequest.newBuilder()
                .setCardNumber("4111111111111111")
                .setCardExpiry("12/27")
                .setCvv("123")
                .setCardholderName("Jane Doe")
                .setAmount("99.99")
                .setCurrencyCode("USD")
                .build();

        transactionGrpcService.initiateTransaction(request, responseObserver);

        ArgumentCaptor<InitiateTransactionResponse> captor = ArgumentCaptor.forClass(InitiateTransactionResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();

        InitiateTransactionResponse response = captor.getValue();
        assertThat(response.getTransactionId()).isEqualTo(txId.toString());
        assertThat(response.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void shouldCallTransactionServiceWithCardNumberAndFields() {
        when(transactionService.initiateTransaction(any(), any(), any(), any(), any()))
                .thenReturn(UUID.randomUUID());

        InitiateTransactionRequest request = InitiateTransactionRequest.newBuilder()
                .setCardNumber("4111111111111111")
                .setCardExpiry("12/27")
                .setCvv("999")
                .setCardholderName("John Smith")
                .setAmount("200.00")
                .setCurrencyCode("EUR")
                .build();

        transactionGrpcService.initiateTransaction(request, responseObserver);

        verify(transactionService).initiateTransaction(
                eq("4111111111111111"),
                eq("John Smith"),
                any(),
                eq("EUR"),
                any()
        );
    }

    @Test
    void shouldReturnInvalidArgumentWhenCardNumberMissing() {
        InitiateTransactionRequest request = InitiateTransactionRequest.newBuilder()
                .setCardholderName("Jane Doe")
                .setAmount("50.00")
                .setCurrencyCode("USD")
                .build();

        transactionGrpcService.initiateTransaction(request, responseObserver);

        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver).onError(errorCaptor.capture());
        verify(transactionService, never()).initiateTransaction(any(), any(), any(), any(), any());
        assertThat(errorCaptor.getValue()).isInstanceOf(StatusRuntimeException.class);
    }

    @Test
    void shouldReturnInvalidArgumentWhenCardholderNameMissing() {
        InitiateTransactionRequest request = InitiateTransactionRequest.newBuilder()
                .setCardNumber("4111111111111111")
                .setAmount("50.00")
                .setCurrencyCode("USD")
                .build();

        transactionGrpcService.initiateTransaction(request, responseObserver);

        verify(responseObserver).onError(any(StatusRuntimeException.class));
        verify(transactionService, never()).initiateTransaction(any(), any(), any(), any(), any());
    }

    @Test
    void shouldReturnInvalidArgumentWhenAmountMissing() {
        InitiateTransactionRequest request = InitiateTransactionRequest.newBuilder()
                .setCardNumber("4111111111111111")
                .setCardholderName("Jane")
                .setCurrencyCode("USD")
                .build();

        transactionGrpcService.initiateTransaction(request, responseObserver);

        verify(responseObserver).onError(any(StatusRuntimeException.class));
        verify(transactionService, never()).initiateTransaction(any(), any(), any(), any(), any());
    }

    // ── getTransaction ─────────────────────────────────────────────────────────

    @Test
    void getTransactionShouldReturnAllFieldsExceptCardToken() {
        UUID txId = UUID.randomUUID();
        Instant now = Instant.now();
        Transaction tx = buildTransaction(txId, now);
        when(transactionService.getTransaction(txId.toString())).thenReturn(Optional.of(tx));

        transactionGrpcService.getTransaction(
                GetTransactionRequest.newBuilder().setTransactionId(txId.toString()).build(),
                getResponseObserver);

        ArgumentCaptor<GetTransactionResponse> captor = ArgumentCaptor.forClass(GetTransactionResponse.class);
        verify(getResponseObserver).onNext(captor.capture());
        verify(getResponseObserver).onCompleted();

        GetTransactionResponse resp = captor.getValue();
        assertThat(resp.getTransactionId()).isEqualTo(txId.toString());
        assertThat(resp.getMaskedCardNumber()).isEqualTo("****-****-****-1111");
        assertThat(resp.getCardholderName()).isEqualTo("Test User");
        assertThat(resp.getAmount()).isEqualTo("50.00");
        assertThat(resp.getCurrencyCode()).isEqualTo("USD");
        assertThat(resp.getStatus()).isEqualTo("AUTHORIZED");
        assertThat(resp.getCreatedAt()).isNotBlank();
        assertThat(resp.getUpdatedAt()).isNotBlank();
    }

    @Test
    void getTransactionShouldReturnNotFoundForUnknownId() {
        UUID unknown = UUID.randomUUID();
        when(transactionService.getTransaction(unknown.toString())).thenReturn(Optional.empty());

        transactionGrpcService.getTransaction(
                GetTransactionRequest.newBuilder().setTransactionId(unknown.toString()).build(),
                getResponseObserver);

        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(getResponseObserver).onError(errorCaptor.capture());
        verify(getResponseObserver, never()).onNext(any());

        assertThat(errorCaptor.getValue()).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode().name())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void getTransactionShouldReturnInvalidArgumentForBlankId() {
        transactionGrpcService.getTransaction(
                GetTransactionRequest.newBuilder().setTransactionId("").build(),
                getResponseObserver);

        verify(getResponseObserver).onError(any(StatusRuntimeException.class));
        verify(transactionService, never()).getTransaction(any());
    }

    @Test
    void getTransactionShouldReturnInvalidArgumentForMalformedUuid() {
        transactionGrpcService.getTransaction(
                GetTransactionRequest.newBuilder().setTransactionId("not-a-uuid").build(),
                getResponseObserver);

        verify(getResponseObserver).onError(any(StatusRuntimeException.class));
    }

    private Transaction buildTransaction(UUID id, Instant timestamp) {
        Transaction tx = new Transaction();
        tx.setId(id);
        tx.setCardToken("vault:v1:opaque-token");
        tx.setMaskedCardNumber("****-****-****-1111");
        tx.setCardholderName("Test User");
        tx.setAmount(new BigDecimal("50.00"));
        tx.setCurrencyCode("USD");
        tx.setStatus(TransactionStatus.AUTHORIZED);
        tx.setCreatedAt(timestamp);  // null triggers the null-branch in getTransaction
        tx.setUpdatedAt(timestamp);
        return tx;
    }

    @Test
    void shouldReturnInvalidArgumentWhenCurrencyCodeMissing() {
        InitiateTransactionRequest request = InitiateTransactionRequest.newBuilder()
                .setCardNumber("4111111111111111")
                .setCardholderName("Jane Doe")
                .setAmount("50.00")
                .build();

        transactionGrpcService.initiateTransaction(request, responseObserver);

        verify(responseObserver).onError(any(StatusRuntimeException.class));
        verify(transactionService, never()).initiateTransaction(any(), any(), any(), any(), any());
    }

    @Test
    void shouldReturnInvalidArgumentWhenAmountIsNotNumeric() {
        InitiateTransactionRequest request = InitiateTransactionRequest.newBuilder()
                .setCardNumber("4111111111111111")
                .setCardholderName("Jane Doe")
                .setAmount("not-a-number")
                .setCurrencyCode("USD")
                .build();

        transactionGrpcService.initiateTransaction(request, responseObserver);

        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver).onError(errorCaptor.capture());
        assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode().name())
                .isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void getTransactionShouldReturnInvalidArgumentWhenServiceThrowsIllegalArgument() {
        UUID txId = UUID.randomUUID();
        when(transactionService.getTransaction(txId.toString()))
                .thenThrow(new IllegalArgumentException("bad uuid"));

        transactionGrpcService.getTransaction(
                GetTransactionRequest.newBuilder().setTransactionId(txId.toString()).build(),
                getResponseObserver);

        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(getResponseObserver).onError(errorCaptor.capture());
        assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode().name())
                .isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void getTransactionShouldReturnInternalErrorWhenServiceThrows() {
        UUID txId = UUID.randomUUID();
        when(transactionService.getTransaction(txId.toString()))
                .thenThrow(new RuntimeException("db down"));

        transactionGrpcService.getTransaction(
                GetTransactionRequest.newBuilder().setTransactionId(txId.toString()).build(),
                getResponseObserver);

        ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(getResponseObserver).onError(errorCaptor.capture());
        assertThat(((StatusRuntimeException) errorCaptor.getValue()).getStatus().getCode().name())
                .isEqualTo("INTERNAL");
    }

    @Test
    void getTransactionShouldReturnEmptyStringsForNullTimestamps() {
        UUID txId = UUID.randomUUID();
        Transaction tx = buildTransaction(txId, null);
        when(transactionService.getTransaction(txId.toString())).thenReturn(Optional.of(tx));

        transactionGrpcService.getTransaction(
                GetTransactionRequest.newBuilder().setTransactionId(txId.toString()).build(),
                getResponseObserver);

        ArgumentCaptor<GetTransactionResponse> captor = ArgumentCaptor.forClass(GetTransactionResponse.class);
        verify(getResponseObserver).onNext(captor.capture());
        assertThat(captor.getValue().getCreatedAt()).isEmpty();
        assertThat(captor.getValue().getUpdatedAt()).isEmpty();
    }

    @Test
    void shouldReturnInternalErrorWhenServiceThrows() {
        when(transactionService.initiateTransaction(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Vault down"));

        InitiateTransactionRequest request = InitiateTransactionRequest.newBuilder()
                .setCardNumber("4111111111111111")
                .setCardExpiry("12/27")
                .setCvv("123")
                .setCardholderName("Jane Doe")
                .setAmount("99.99")
                .setCurrencyCode("USD")
                .build();

        transactionGrpcService.initiateTransaction(request, responseObserver);

        verify(responseObserver).onError(any(StatusRuntimeException.class));
        verify(responseObserver, never()).onNext(any());
    }
}
