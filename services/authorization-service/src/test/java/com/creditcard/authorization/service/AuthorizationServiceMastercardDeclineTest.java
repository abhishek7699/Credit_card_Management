package com.creditcard.authorization.service;

import com.creditcard.authorization.domain.AuthorizationOutcome;
import com.creditcard.authorization.domain.AuthorizationRecord;
import com.creditcard.authorization.domain.ReasonCode;
import com.creditcard.authorization.kafka.event.AuthorizationResultEvent;
import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import com.creditcard.authorization.processor.PaymentProcessor;
import com.creditcard.authorization.processor.ProcessorDecision;
import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec 014 phase 2: a Mastercard decline is recorded with its gateway code, and the event stays a PROCESSOR_DECLINED. */
class AuthorizationServiceMastercardDeclineTest {

    @Test
    void declineRecordsTheProcessorCodeAndPublishesProcessorDeclined() {
        PaymentProcessor processor = mock(PaymentProcessor.class);
        AuthorizationRecordRepository records = mock(AuthorizationRecordRepository.class);
        when(processor.authorize(any())).thenReturn(ProcessorDecision.declinedByProcessor("INSUFFICIENT_FUNDS"));

        Optional<AuthorizationResultEvent> result = new AuthorizationService(processor, records).authorize(event());

        assertThat(result).isPresent();
        assertThat(result.get().getEventType()).isEqualTo("TransactionDeclined");
        assertThat(result.get().getPayload().getReasonCode()).isEqualTo(ReasonCode.PROCESSOR_DECLINED.name());
        ArgumentCaptor<AuthorizationRecord> saved = ArgumentCaptor.forClass(AuthorizationRecord.class);
        verify(records).save(saved.capture());
        assertThat(saved.getValue().getOutcome()).isEqualTo(AuthorizationOutcome.DECLINED);
        assertThat(saved.getValue().getProcessorReasonCode()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    private static TransactionInitiatedEvent event() {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("TransactionInitiated");
        TransactionInitiatedEvent.Payload payload = new TransactionInitiatedEvent.Payload();
        payload.setTransactionId(UUID.randomUUID());
        payload.setCardToken("session-ref");
        payload.setMaskedCardNumber("****-****-****-1111");
        payload.setCardholderName("Jane Smith");
        payload.setAmount(new BigDecimal("42.00"));
        payload.setCurrencyCode("USD");
        payload.setMerchantId("merchant-1");
        event.setPayload(payload);
        return event;
    }
}
