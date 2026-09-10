package com.creditcard.authorization.contract;

import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire shape of {@code transaction.initiated} as published by transaction-service:
 * an envelope with a nested {@code payload}. A regression here means authorization-service can
 * no longer read the events (the "Received null or invalid event payload" incident).
 */
class TransactionInitiatedEventSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldDeserializeTheEnvelopePublishedByTransactionService() throws Exception {
        UUID txId = UUID.randomUUID();
        // amount as a JSON number — how transaction-service's Jackson serializes BigDecimal
        String json = """
                {
                  "eventId": "550e8400-e29b-41d4-a716-446655440000",
                  "eventType": "TransactionInitiated",
                  "sourceService": "transaction-service",
                  "traceId": "64c6f34a2b8e4d9f",
                  "occurredAt": "2026-06-11T10:30:00.000Z",
                  "payload": {
                    "transactionId": "%s",
                    "cardToken": "vault:v1:8SDd3WHDOjf7mq69",
                    "maskedCardNumber": "****-****-****-1234",
                    "cardholderName": "Jane Smith",
                    "amount": 99.99,
                    "currencyCode": "USD",
                    "merchantId": "merchant-e2e-001"
                  }
                }
                """.formatted(txId);

        TransactionInitiatedEvent event = mapper.readValue(json, TransactionInitiatedEvent.class);

        assertThat(event.getEventId()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(event.getEventType()).isEqualTo("TransactionInitiated");
        assertThat(event.getSourceService()).isEqualTo("transaction-service");
        assertThat(event.getTraceId()).isEqualTo("64c6f34a2b8e4d9f");
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getPayload()).isNotNull();
        assertThat(event.getPayload().getTransactionId()).isEqualTo(txId);
        assertThat(event.getPayload().getCardToken()).isEqualTo("vault:v1:8SDd3WHDOjf7mq69");
        assertThat(event.getPayload().getMaskedCardNumber()).isEqualTo("****-****-****-1234");
        assertThat(event.getPayload().getCardholderName()).isEqualTo("Jane Smith");
        assertThat(event.getPayload().getAmount()).isEqualByComparingTo("99.99");
        assertThat(event.getPayload().getCurrencyCode()).isEqualTo("USD");
        assertThat(event.getPayload().getMerchantId()).isEqualTo("merchant-e2e-001");
    }

    @Test
    void shouldAcceptAmountAsStringPerContractDoc() throws Exception {
        String json = """
                { "eventId": "e", "eventType": "TransactionInitiated",
                  "payload": { "transactionId": "%s", "cardToken": "vault:v1:x",
                               "amount": "250.00", "currencyCode": "USD" } }
                """.formatted(UUID.randomUUID());

        TransactionInitiatedEvent event = mapper.readValue(json, TransactionInitiatedEvent.class);

        assertThat(event.getPayload().getAmount()).isEqualByComparingTo("250.00");
    }

    @Test
    void shouldIgnoreUnknownFieldsAtBothLevels() throws Exception {
        String json = """
                { "eventId": "e", "eventType": "TransactionInitiated", "extraTop": true,
                  "payload": { "transactionId": "%s", "cardToken": "vault:v1:x",
                               "amount": 100.00, "currencyCode": "USD", "extraNested": 42 } }
                """.formatted(UUID.randomUUID());

        TransactionInitiatedEvent event = mapper.readValue(json, TransactionInitiatedEvent.class);

        assertThat(event.getEventId()).isEqualTo("e");
        assertThat(event.getPayload().getCardToken()).isEqualTo("vault:v1:x");
    }

    @Test
    void shouldRoundTripThroughJson() throws Exception {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId("evt-1");
        event.setEventType("TransactionInitiated");
        event.setSourceService("transaction-service");
        TransactionInitiatedEvent.Payload payload = new TransactionInitiatedEvent.Payload();
        payload.setTransactionId(UUID.randomUUID());
        payload.setCardToken("vault:v1:rt");
        payload.setMerchantId("m-1");
        event.setPayload(payload);

        String json = mapper.writeValueAsString(event);
        TransactionInitiatedEvent back = mapper.readValue(json, TransactionInitiatedEvent.class);

        assertThat(back.getPayload().getTransactionId()).isEqualTo(payload.getTransactionId());
        assertThat(back.getPayload().getCardToken()).isEqualTo("vault:v1:rt");
        assertThat(back.getPayload().getMerchantId()).isEqualTo("m-1");
    }
}
