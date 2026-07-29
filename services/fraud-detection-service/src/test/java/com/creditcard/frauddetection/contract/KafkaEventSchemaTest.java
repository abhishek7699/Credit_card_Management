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
        original.setTransactionId(UUID.randomUUID());
        original.setCardToken("vault:v1:schema-tok");
        original.setAmount(new BigDecimal("123.45"));
        original.setCurrencyCode("USD");
        original.setOccurredAt(Instant.parse("2026-06-12T10:00:00Z"));

        String json = objectMapper.writeValueAsString(original);
        TransactionInitiatedEvent deserialized = objectMapper.readValue(json, TransactionInitiatedEvent.class);

        assertThat(deserialized.getEventId()).isEqualTo("evt-schema-001");
        assertThat(deserialized.getEventType()).isEqualTo("TransactionInitiated");
        assertThat(deserialized.getTransactionId()).isEqualTo(original.getTransactionId());
        assertThat(deserialized.getCardToken()).isEqualTo("vault:v1:schema-tok");
        assertThat(deserialized.getAmount()).isEqualByComparingTo("123.45");
        assertThat(deserialized.getCurrencyCode()).isEqualTo("USD");
        assertThat(deserialized.getOccurredAt()).isEqualTo(Instant.parse("2026-06-12T10:00:00Z"));
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
    void transactionInitiatedEventShouldIgnoreUnknownFields() throws Exception {
        String json = """
                {
                  "eventId": "evt-unknown",
                  "eventType": "TransactionInitiated",
                  "transactionId": "00000000-0000-0000-0000-000000000001",
                  "cardToken": "vault:v1:tok",
                  "amount": 50.00,
                  "currencyCode": "USD",
                  "occurredAt": "2026-06-12T10:00:00Z",
                  "unknownField": "should be ignored"
                }
                """;

        TransactionInitiatedEvent event = objectMapper.readValue(json, TransactionInitiatedEvent.class);
        assertThat(event.getEventId()).isEqualTo("evt-unknown");
    }
}
