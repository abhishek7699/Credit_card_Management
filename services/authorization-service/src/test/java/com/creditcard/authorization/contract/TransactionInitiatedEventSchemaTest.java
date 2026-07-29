package com.creditcard.authorization.contract;

import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionInitiatedEventSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldHaveAllRequiredFields() {
        UUID txId = UUID.randomUUID();
        Instant now = Instant.now();

        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId("evt-schema-001");
        event.setEventType("TransactionInitiated");
        event.setTransactionId(txId);
        event.setCardToken("vault:v1:token-schema");
        event.setAmount(new BigDecimal("150.00"));
        event.setCurrencyCode("USD");
        event.setOccurredAt(now);

        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getEventType()).isEqualTo("TransactionInitiated");
        assertThat(event.getTransactionId()).isNotNull();
        assertThat(event.getCardToken()).isNotNull();
        assertThat(event.getAmount()).isNotNull();
        assertThat(event.getCurrencyCode()).hasSize(3);
        assertThat(event.getOccurredAt()).isNotNull();
    }

    @Test
    void shouldDeserializeFromJson() throws Exception {
        UUID txId = UUID.randomUUID();
        String json = """
                {
                  "eventId": "evt-json-001",
                  "eventType": "TransactionInitiated",
                  "transactionId": "%s",
                  "cardToken": "vault:v1:token-json",
                  "amount": "250.00",
                  "currencyCode": "EUR",
                  "occurredAt": "2026-06-11T10:00:00Z"
                }
                """.formatted(txId);

        TransactionInitiatedEvent event = mapper.readValue(json, TransactionInitiatedEvent.class);

        assertThat(event.getEventId()).isEqualTo("evt-json-001");
        assertThat(event.getEventType()).isEqualTo("TransactionInitiated");
        assertThat(event.getTransactionId()).isEqualTo(txId);
        assertThat(event.getCardToken()).isEqualTo("vault:v1:token-json");
        assertThat(event.getAmount()).isEqualByComparingTo("250.00");
        assertThat(event.getCurrencyCode()).isEqualTo("EUR");
        assertThat(event.getOccurredAt()).isNotNull();
    }

    @Test
    void shouldIgnoreUnknownFieldsDuringDeserialization() throws Exception {
        String json = """
                {
                  "eventId": "evt-unknown-001",
                  "eventType": "TransactionInitiated",
                  "transactionId": "%s",
                  "cardToken": "vault:v1:token",
                  "amount": "100.00",
                  "currencyCode": "USD",
                  "occurredAt": "2026-06-11T10:00:00Z",
                  "unknownField": "someValue",
                  "anotherUnknown": 42
                }
                """.formatted(UUID.randomUUID());

        TransactionInitiatedEvent event = mapper.readValue(json, TransactionInitiatedEvent.class);
        assertThat(event.getEventId()).isEqualTo("evt-unknown-001");
    }
}
