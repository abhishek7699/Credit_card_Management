package com.creditcard.authorization.kafka.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionTimedOutEventTest {

    @Test
    void readsTheEnvelopeWrittenByTransactionService() throws Exception {
        UUID txId = UUID.randomUUID();
        String json = """
                {"eventId":"evt-9","eventType":"TransactionTimedOut","sourceService":"transaction-service",
                 "traceId":"t-9","occurredAt":"2026-11-12T12:00:00Z",
                 "payload":{"transactionId":"%s","cardToken":"vault:v1:tok"}}
                """.formatted(txId);

        TransactionTimedOutEvent event = new ObjectMapper().findAndRegisterModules()
                .readValue(json, TransactionTimedOutEvent.class);

        assertThat(event.getEventId()).isEqualTo("evt-9");
        assertThat(event.getEventType()).isEqualTo("TransactionTimedOut");
        assertThat(event.getSourceService()).isEqualTo("transaction-service");
        assertThat(event.getTraceId()).isEqualTo("t-9");
        assertThat(event.getOccurredAt()).isEqualTo(Instant.parse("2026-11-12T12:00:00Z"));
        assertThat(event.getPayload().getTransactionId()).isEqualTo(txId);
        assertThat(event.getPayload().getCardToken()).isEqualTo("vault:v1:tok");
    }

    @Test
    void ignoresFieldsItDoesNotKnow() throws Exception {
        String json = "{\"eventId\":\"e\",\"unknownField\":1,\"payload\":{\"transactionId\":\""
                + UUID.randomUUID() + "\",\"extra\":true}}";

        TransactionTimedOutEvent event = new ObjectMapper().readValue(json, TransactionTimedOutEvent.class);

        assertThat(event.getEventId()).isEqualTo("e");
        assertThat(event.getPayload().getCardToken()).isNull();
    }
}
