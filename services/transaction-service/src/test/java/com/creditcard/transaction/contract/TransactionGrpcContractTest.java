package com.creditcard.transaction.contract;

import com.creditcard.transaction.grpc.v1.GetTransactionRequest;
import com.creditcard.transaction.grpc.v1.GetTransactionResponse;
import com.creditcard.transaction.grpc.v1.InitiateTransactionRequest;
import com.creditcard.transaction.grpc.v1.InitiateTransactionResponse;
import com.google.protobuf.Descriptors.Descriptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that both RPCs comply with the proto contract in proto/v1/transaction_service.proto.
 * These are structural tests — they confirm field names and types match the generated stubs
 * without spinning up the full application context.
 */
class TransactionGrpcContractTest {

    // ── InitiateTransaction ───────────────────────────────────────────────────

    @Test
    void initiateTransactionRequestHasAllRequiredFields() {
        InitiateTransactionRequest request = InitiateTransactionRequest.newBuilder()
                .setCardNumber("4111111111111111")
                .setCardExpiry("12/27")
                .setCvv("123")
                .setCardholderName("Test User")
                .setAmount("99.99")
                .setCurrencyCode("USD")
                .build();

        assertThat(request.getCardNumber()).isEqualTo("4111111111111111");
        assertThat(request.getCardExpiry()).isEqualTo("12/27");
        assertThat(request.getCvv()).isEqualTo("123");
        assertThat(request.getCardholderName()).isEqualTo("Test User");
        assertThat(request.getAmount()).isEqualTo("99.99");
        assertThat(request.getCurrencyCode()).isEqualTo("USD");
    }

    @Test
    void initiateTransactionResponseHasTransactionIdAndStatus() {
        InitiateTransactionResponse response = InitiateTransactionResponse.newBuilder()
                .setTransactionId("some-uuid")
                .setStatus("PENDING")
                .build();

        assertThat(response.getTransactionId()).isEqualTo("some-uuid");
        assertThat(response.getStatus()).isEqualTo("PENDING");
    }

    // ── GetTransaction ────────────────────────────────────────────────────────

    @Test
    void getTransactionRequestHasTransactionIdField() {
        GetTransactionRequest request = GetTransactionRequest.newBuilder()
                .setTransactionId("tx-uuid-123")
                .build();

        assertThat(request.getTransactionId()).isEqualTo("tx-uuid-123");
    }

    @Test
    void getTransactionResponseHasAllExpectedFields() {
        GetTransactionResponse response = GetTransactionResponse.newBuilder()
                .setTransactionId("tx-id")
                .setMaskedCardNumber("****-****-****-1234")
                .setCardholderName("Test User")
                .setAmount("99.99")
                .setCurrencyCode("USD")
                .setStatus("AUTHORIZED")
                .setCreatedAt("2026-01-01T00:00:00Z")
                .setUpdatedAt("2026-01-01T00:01:00Z")
                .build();

        assertThat(response.getTransactionId()).isEqualTo("tx-id");
        assertThat(response.getMaskedCardNumber()).startsWith("****");
        assertThat(response.getCardholderName()).isEqualTo("Test User");
        assertThat(response.getAmount()).isEqualTo("99.99");
        assertThat(response.getCurrencyCode()).isEqualTo("USD");
        assertThat(response.getStatus()).isEqualTo("AUTHORIZED");
        assertThat(response.getCreatedAt()).isNotBlank();
        assertThat(response.getUpdatedAt()).isNotBlank();
    }

    @Test
    void getTransactionResponseDoesNotHaveCardTokenField() {
        // Security invariant: the proto descriptor must NOT define a card_token field.
        // If someone adds card_token to GetTransactionResponse, this test catches it.
        Descriptor descriptor = GetTransactionResponse.getDescriptor();
        assertThat(descriptor.findFieldByName("card_token"))
                .as("GetTransactionResponse must never expose card_token")
                .isNull();
    }

    @Test
    void initiateTransactionResponseDoesNotHaveCardTokenField() {
        Descriptor descriptor = InitiateTransactionResponse.getDescriptor();
        assertThat(descriptor.findFieldByName("card_token"))
                .as("InitiateTransactionResponse must never expose card_token")
                .isNull();
    }
}
