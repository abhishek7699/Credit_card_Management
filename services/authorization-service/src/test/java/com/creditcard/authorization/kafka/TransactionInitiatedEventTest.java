package com.creditcard.authorization.kafka;

import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionInitiatedEventTest {

    @Test
    void shouldHoldAllEnvelopeAndPayloadFields() {
        UUID txId = UUID.randomUUID();
        Instant now = Instant.now();

        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId("evt-001");
        event.setEventType("TransactionInitiated");
        event.setSourceService("transaction-service");
        event.setTraceId("trace-9");
        event.setOccurredAt(now);

        TransactionInitiatedEvent.Payload payload = new TransactionInitiatedEvent.Payload();
        payload.setTransactionId(txId);
        payload.setCardToken("vault:v1:token-abc");
        payload.setMaskedCardNumber("****-****-****-1234");
        payload.setCardholderName("Jane Smith");
        payload.setAmount(new BigDecimal("250.00"));
        payload.setCurrencyCode("USD");
        payload.setMerchantId("merchant-77");
        event.setPayload(payload);

        assertThat(event.getEventId()).isEqualTo("evt-001");
        assertThat(event.getEventType()).isEqualTo("TransactionInitiated");
        assertThat(event.getSourceService()).isEqualTo("transaction-service");
        assertThat(event.getTraceId()).isEqualTo("trace-9");
        assertThat(event.getOccurredAt()).isEqualTo(now);
        assertThat(event.getPayload().getTransactionId()).isEqualTo(txId);
        assertThat(event.getPayload().getCardToken()).isEqualTo("vault:v1:token-abc");
        assertThat(event.getPayload().getMaskedCardNumber()).isEqualTo("****-****-****-1234");
        assertThat(event.getPayload().getCardholderName()).isEqualTo("Jane Smith");
        assertThat(event.getPayload().getAmount()).isEqualByComparingTo("250.00");
        assertThat(event.getPayload().getCurrencyCode()).isEqualTo("USD");
        assertThat(event.getPayload().getMerchantId()).isEqualTo("merchant-77");
    }

    @Test
    void shouldDefaultConstructWithNulls() {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        assertThat(event.getEventId()).isNull();
        assertThat(event.getPayload()).isNull();
    }
}
