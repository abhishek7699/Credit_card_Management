package com.creditcard.frauddetection.contract;

import com.creditcard.frauddetection.kafka.event.FraudAlertRaisedEvent;
import com.creditcard.frauddetection.kafka.event.TransactionInitiatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaEventSchemaTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void transactionInitiatedEventShouldSurviveJsonRoundTrip() throws Exception {
        TransactionInitiatedEvent original = new TransactionInitiatedEvent();
        original.setEventId("evt-schema-001");
        original.setEventType("TransactionInitiated");
        original.setSourceService("transaction-service");
        original.setTraceId("trace-schema-001");
        original.setOccurredAt(Instant.parse("2026-06-12T10:00:00Z"));

        TransactionInitiatedEvent.Payload payload = new TransactionInitiatedEvent.Payload();
        payload.setTransactionId(UUID.randomUUID());
        payload.setCardToken("vault:v1:schema-tok");
        payload.setMaskedCardNumber("****-****-****-1234");
        payload.setCardholderName("Jane Smith");
        payload.setAmount(new BigDecimal("123.45"));
        payload.setCurrencyCode("USD");
        payload.setMerchantId("merchant-schema-001");
        original.setPayload(payload);

        String json = objectMapper.writeValueAsString(original);
        TransactionInitiatedEvent deserialized = objectMapper.readValue(json, TransactionInitiatedEvent.class);

        assertThat(deserialized.getEventId()).isEqualTo("evt-schema-001");
        assertThat(deserialized.getEventType()).isEqualTo("TransactionInitiated");
        assertThat(deserialized.getSourceService()).isEqualTo("transaction-service");
        assertThat(deserialized.getTraceId()).isEqualTo("trace-schema-001");
        assertThat(deserialized.getOccurredAt()).isEqualTo(Instant.parse("2026-06-12T10:00:00Z"));
        assertThat(deserialized.getPayload()).isNotNull();
        assertThat(deserialized.getPayload().getTransactionId()).isEqualTo(payload.getTransactionId());
        assertThat(deserialized.getPayload().getCardToken()).isEqualTo("vault:v1:schema-tok");
        assertThat(deserialized.getPayload().getMaskedCardNumber()).isEqualTo("****-****-****-1234");
        assertThat(deserialized.getPayload().getCardholderName()).isEqualTo("Jane Smith");
        assertThat(deserialized.getPayload().getAmount()).isEqualByComparingTo("123.45");
        assertThat(deserialized.getPayload().getCurrencyCode()).isEqualTo("USD");
        assertThat(deserialized.getPayload().getMerchantId()).isEqualTo("merchant-schema-001");
    }

    @Test
    void fraudAlertRaisedEventShouldSerializeAllRequiredFields() throws Exception {
        UUID alertId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        Instant detectedAt = Instant.parse("2026-06-12T10:00:00Z");

        FraudAlertRaisedEvent event = FraudAlertRaisedEvent.of(
                alertId, transactionId, "vault:v1:tok",
                List.of("VELOCITY_EXCEEDED", "AMOUNT_ANOMALY"),
                detectedAt, "trace-abc123");

        String json = objectMapper.writeValueAsString(event);
        FraudAlertRaisedEvent deserialized = objectMapper.readValue(json, FraudAlertRaisedEvent.class);

        assertThat(deserialized.getEventId()).isNotNull();
        assertThat(deserialized.getEventType()).isEqualTo("FraudAlertRaised");
        assertThat(deserialized.getAlertId()).isEqualTo(alertId);
        assertThat(deserialized.getTransactionId()).isEqualTo(transactionId);
        assertThat(deserialized.getCardToken()).isEqualTo("vault:v1:tok");
        assertThat(deserialized.getTriggeredRuleTypes()).containsExactly("VELOCITY_EXCEEDED", "AMOUNT_ANOMALY");
        assertThat(deserialized.getDetectedAt()).isEqualTo(detectedAt);
        assertThat(deserialized.getSourceService()).isEqualTo("fraud-detection-service");
        assertThat(deserialized.getTraceId()).isEqualTo("trace-abc123");
    }

    @Test
    void transactionInitiatedEventShouldDeserializeTheEnvelopePublishedByTransactionService() throws Exception {
        UUID txId = UUID.randomUUID();
        String json = """
                {
                  "eventId": "550e8400-e29b-41d4-a716-446655440000",
                  "eventType": "TransactionInitiated",
                  "sourceService": "transaction-service",
                  "traceId": "64c6f34a2b8e4d9f",
                  "occurredAt": "2026-06-11T10:30:00.000Z",
                  "payload": {
                    "transactionId": "%s",
                    "cardToken": "vault:v1:8SDd3WHDOjf7mq69CyCqYjBXAiQQAVZRkFM13ok481zo",
                    "maskedCardNumber": "****-****-****-1234",
                    "cardholderName": "Jane Smith",
                    "amount": 99.99,
                    "currencyCode": "USD",
                    "merchantId": "merchant-e2e-001"
                  }
                }
                """.formatted(txId);

        TransactionInitiatedEvent event = objectMapper.readValue(json, TransactionInitiatedEvent.class);

        assertThat(event.getEventId()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(event.getSourceService()).isEqualTo("transaction-service");
        assertThat(event.getPayload().getTransactionId()).isEqualTo(txId);
        assertThat(event.getPayload().getCardToken()).isEqualTo("vault:v1:8SDd3WHDOjf7mq69CyCqYjBXAiQQAVZRkFM13ok481zo");
        assertThat(event.getPayload().getAmount()).isEqualByComparingTo("99.99");
        assertThat(event.getPayload().getMerchantId()).isEqualTo("merchant-e2e-001");
    }

    @Test
    void transactionInitiatedEventShouldIgnoreUnknownFieldsAtBothLevels() throws Exception {
        String json = """
                {
                  "eventId": "evt-unknown",
                  "eventType": "TransactionInitiated",
                  "extraTopLevelField": "should be ignored",
                  "payload": {
                    "transactionId": "00000000-0000-0000-0000-000000000001",
                    "cardToken": "vault:v1:tok",
                    "amount": 50.00,
                    "currencyCode": "USD",
                    "extraNestedField": "should also be ignored"
                  }
                }
                """;

        TransactionInitiatedEvent event = objectMapper.readValue(json, TransactionInitiatedEvent.class);
        assertThat(event.getEventId()).isEqualTo("evt-unknown");
        assertThat(event.getPayload().getCardToken()).isEqualTo("vault:v1:tok");
    }
}
