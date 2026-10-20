package com.creditcard.notification.contract;

import com.creditcard.notification.kafka.event.TransactionStatusEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the envelope contract on {@code transaction.status.updated}.
 * Before this event class gained a nested {@code payload}, a real message from
 * authorization-service's {@code AuthorizationResultEvent} deserialized with
 * transactionId/cardToken/reasonCode all null, and NotificationService silently
 * discarded every transaction-status event.
 */
class TransactionStatusEventSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void shouldDeserializeTheEnvelopePublishedByAuthorizationService() throws Exception {
        String json = """
                {
                  "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                  "eventType": "TransactionAuthorized",
                  "sourceService": "authorization-service",
                  "traceId": "64c6f34a2b8e4d9f",
                  "occurredAt": "2026-06-11T10:30:00.250Z",
                  "payload": {
                    "transactionId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                    "cardToken": "vault:v1:8SDd3WHDOjf7mq69CyCqYjBXAiQQAVZRkFM13ok481zo",
                    "outcome": "AUTHORIZED",
                    "reasonCode": null
                  }
                }
                """;

        TransactionStatusEvent event = mapper.readValue(json, TransactionStatusEvent.class);

        assertThat(event.getEventId()).isEqualTo("3fa85f64-5717-4562-b3fc-2c963f66afa6");
        assertThat(event.getEventType()).isEqualTo("TransactionAuthorized");
        assertThat(event.getSourceService()).isEqualTo("authorization-service");
        assertThat(event.getTraceId()).isEqualTo("64c6f34a2b8e4d9f");
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getPayload()).isNotNull();
        assertThat(event.getPayload().getTransactionId()).isEqualTo("7c9e6679-7425-40de-944b-e07fc1f90ae7");
        assertThat(event.getPayload().getCardToken()).isEqualTo("vault:v1:8SDd3WHDOjf7mq69CyCqYjBXAiQQAVZRkFM13ok481zo");
        assertThat(event.getPayload().getOutcome()).isEqualTo("AUTHORIZED");
        assertThat(event.getPayload().getReasonCode()).isNull();
    }

    @Test
    void shouldDeserializeDeclinedEventWithReasonCode() throws Exception {
        String json = """
                {
                  "eventId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
                  "eventType": "TransactionDeclined",
                  "sourceService": "authorization-service",
                  "traceId": "64c6f34a2b8e4d9f",
                  "occurredAt": "2026-06-11T10:30:00.280Z",
                  "payload": {
                    "transactionId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                    "cardToken": "vault:v1:tok",
                    "outcome": "DECLINED",
                    "reasonCode": "INSUFFICIENT_CREDIT"
                  }
                }
                """;

        TransactionStatusEvent event = mapper.readValue(json, TransactionStatusEvent.class);

        assertThat(event.getPayload().getOutcome()).isEqualTo("DECLINED");
        assertThat(event.getPayload().getReasonCode()).isEqualTo("INSUFFICIENT_CREDIT");
    }

    @Test
    void shouldIgnoreUnknownFieldsAtBothLevels() throws Exception {
        String json = """
                {
                  "eventId": "evt-unknown",
                  "eventType": "TransactionAuthorized",
                  "extraTopLevelField": "should be ignored",
                  "payload": {
                    "transactionId": "tx-001",
                    "cardToken": "vault:v1:tok",
                    "outcome": "AUTHORIZED",
                    "extraNestedField": "should also be ignored"
                  }
                }
                """;

        TransactionStatusEvent event = mapper.readValue(json, TransactionStatusEvent.class);

        assertThat(event.getEventId()).isEqualTo("evt-unknown");
        assertThat(event.getPayload().getCardToken()).isEqualTo("vault:v1:tok");
    }

    @Test
    void shouldRoundTripThroughJson() throws Exception {
        TransactionStatusEvent event = new TransactionStatusEvent();
        event.setEventId("evt-1");
        event.setEventType("TransactionFailed");
        event.setSourceService("authorization-service");

        TransactionStatusEvent.Payload payload = new TransactionStatusEvent.Payload();
        payload.setTransactionId("tx-rt");
        payload.setCardToken("vault:v1:rt");
        payload.setOutcome("FAILED");
        payload.setReasonCode("INTERNAL_ERROR");
        event.setPayload(payload);

        String json = mapper.writeValueAsString(event);
        TransactionStatusEvent back = mapper.readValue(json, TransactionStatusEvent.class);

        assertThat(back.getPayload().getTransactionId()).isEqualTo("tx-rt");
        assertThat(back.getPayload().getCardToken()).isEqualTo("vault:v1:rt");
        assertThat(back.getPayload().getOutcome()).isEqualTo("FAILED");
        assertThat(back.getPayload().getReasonCode()).isEqualTo("INTERNAL_ERROR");
    }
}
