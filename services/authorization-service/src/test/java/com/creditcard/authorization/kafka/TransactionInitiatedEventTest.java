package com.creditcard.authorization.kafka;

import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionInitiatedEventTest {

    @Test
    void shouldHoldAllFields() {
        UUID txId = UUID.randomUUID();
        Instant now = Instant.now();

        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId("evt-001");
        event.setEventType("TransactionInitiated");
        event.setTransactionId(txId);
        event.setCardToken("vault:v1:token-abc");
        event.setAmount(new BigDecimal("250.00"));
        event.setCurrencyCode("USD");
        event.setOccurredAt(now);

        assertThat(event.getEventId()).isEqualTo("evt-001");
        assertThat(event.getEventType()).isEqualTo("TransactionInitiated");
        assertThat(event.getTransactionId()).isEqualTo(txId);
        assertThat(event.getCardToken()).isEqualTo("vault:v1:token-abc");
        assertThat(event.getAmount()).isEqualByComparingTo("250.00");
        assertThat(event.getCurrencyCode()).isEqualTo("USD");
        assertThat(event.getOccurredAt()).isEqualTo(now);
    }

    @Test
    void shouldDefaultConstructWithNulls() {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        assertThat(event.getEventId()).isNull();
        assertThat(event.getTransactionId()).isNull();
        assertThat(event.getAmount()).isNull();
    }
}
