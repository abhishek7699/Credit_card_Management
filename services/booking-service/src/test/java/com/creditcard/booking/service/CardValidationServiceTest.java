package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.CardDetails;
import com.creditcard.booking.exception.CardNotVerifiedException;
import com.creditcard.booking.exception.CardValidationUnavailableException;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import com.creditcard.transaction.grpc.v1.ValidateCardRequest;
import com.creditcard.transaction.grpc.v1.ValidateCardResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardValidationServiceTest {

    private static final CardDetails CARD = new CardDetails("5102589999999913", "10/50", "6173", "Jane Doe");

    @Mock
    private TransactionServiceGrpc.TransactionServiceBlockingStub stub;
    @Mock
    private TransactionServiceGrpc.TransactionServiceBlockingStub deadlineStub;

    private CardValidationService service;

    @BeforeEach
    void setUp() {
        service = new CardValidationService(stub, 8);
        when(stub.withDeadlineAfter(8, TimeUnit.SECONDS)).thenReturn(deadlineStub);
    }

    private static ValidateCardResponse response(String status) {
        return ValidateCardResponse.newBuilder().setStatus(status).setReferenceId("AVM_abc").setLast4("9913").build();
    }

    @Test
    void matched_returnsOnlyVerdictReferenceAndLast4_andSendsTheCardForThisOneCall() {
        when(deadlineStub.validateCard(any())).thenReturn(response("MATCHED"));

        CardValidationResult result = service.validate(CARD);

        assertThat(result).isEqualTo(new CardValidationResult("MATCHED", "AVM_abc", "9913"));
        ArgumentCaptor<ValidateCardRequest> sent = ArgumentCaptor.forClass(ValidateCardRequest.class);
        verify(deadlineStub).validateCard(sent.capture());
        assertThat(sent.getValue().getCardNumber()).isEqualTo("5102589999999913");
        assertThat(sent.getValue().getCardExpiry()).isEqualTo("10/50");
        assertThat(sent.getValue().getCvv()).isEqualTo("6173");
        assertThat(sent.getValue().getCardholderName()).isEqualTo("Jane Doe");
    }

    @Test
    void notMatched_isRejectedAsCardNotVerified() {
        when(deadlineStub.validateCard(any())).thenReturn(response("NOT_MATCHED"));

        assertThatThrownBy(() -> service.validate(CARD)).isInstanceOf(CardNotVerifiedException.class);
    }

    @Test
    void invalidArgument_isRejectedAsCardNotVerified() {
        when(deadlineStub.validateCard(any())).thenThrow(new StatusRuntimeException(Status.INVALID_ARGUMENT));

        assertThatThrownBy(() -> service.validate(CARD)).isInstanceOf(CardNotVerifiedException.class);
    }

    @Test
    void unavailableTimeoutAndEverythingElse_failClosed() {
        for (Status status : new Status[] {Status.UNAVAILABLE, Status.DEADLINE_EXCEEDED, Status.INTERNAL}) {
            doThrow(new StatusRuntimeException(status)).when(deadlineStub).validateCard(any());

            assertThatThrownBy(() -> service.validate(CARD)).isInstanceOf(CardValidationUnavailableException.class);
        }
    }
}
