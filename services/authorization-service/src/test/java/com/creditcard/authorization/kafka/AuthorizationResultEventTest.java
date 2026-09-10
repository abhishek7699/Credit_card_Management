package com.creditcard.authorization.kafka;

import com.creditcard.authorization.domain.ReasonCode;
import com.creditcard.authorization.kafka.event.AuthorizationResultEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationResultEventTest {

    @Test
    void shouldCreateAuthorizedEventWithEnvelopeAndPayload() {
        UUID txId = UUID.randomUUID();
        AuthorizationResultEvent event = AuthorizationResultEvent.authorized(txId, "vault:v1:tok");

        assertThat(event.getEventType()).isEqualTo("TransactionAuthorized");
        assertThat(event.getSourceService()).isEqualTo("authorization-service");
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getPayload().getTransactionId()).isEqualTo(txId);
        assertThat(event.getPayload().getCardToken()).isEqualTo("vault:v1:tok");
        assertThat(event.getPayload().getOutcome()).isEqualTo("AUTHORIZED");
        assertThat(event.getPayload().getReasonCode()).isNull();
    }

    @Test
    void shouldCreateDeclinedEvent() {
        UUID txId = UUID.randomUUID();
        AuthorizationResultEvent event = AuthorizationResultEvent.declined(txId, "vault:v1:tok", ReasonCode.INSUFFICIENT_CREDIT);

        assertThat(event.getEventType()).isEqualTo("TransactionDeclined");
        assertThat(event.getPayload().getOutcome()).isEqualTo("DECLINED");
        assertThat(event.getPayload().getReasonCode()).isEqualTo(ReasonCode.INSUFFICIENT_CREDIT.name());
    }

    @Test
    void shouldCreateFailedEvent() {
        UUID txId = UUID.randomUUID();
        AuthorizationResultEvent event = AuthorizationResultEvent.failed(txId, "vault:v1:tok", ReasonCode.INTERNAL_ERROR);

        assertThat(event.getEventType()).isEqualTo("TransactionFailed");
        assertThat(event.getPayload().getOutcome()).isEqualTo("FAILED");
        assertThat(event.getPayload().getReasonCode()).isEqualTo(ReasonCode.INTERNAL_ERROR.name());
    }

    @Test
    void shouldSupportEnvelopeGettersAndSetters() {
        AuthorizationResultEvent event = new AuthorizationResultEvent();
        Instant now = Instant.now();

        event.setEventId("id-1");
        event.setEventType("TransactionAuthorized");
        event.setSourceService("authorization-service");
        event.setTraceId("trace-1");
        event.setOccurredAt(now);

        AuthorizationResultEvent.Payload payload = new AuthorizationResultEvent.Payload();
        UUID txId = UUID.randomUUID();
        payload.setTransactionId(txId);
        payload.setCardToken("vault:v1:tok");
        payload.setOutcome("AUTHORIZED");
        payload.setReasonCode("NONE");
        event.setPayload(payload);

        assertThat(event.getEventId()).isEqualTo("id-1");
        assertThat(event.getEventType()).isEqualTo("TransactionAuthorized");
        assertThat(event.getSourceService()).isEqualTo("authorization-service");
        assertThat(event.getTraceId()).isEqualTo("trace-1");
        assertThat(event.getOccurredAt()).isEqualTo(now);
        assertThat(event.getPayload().getTransactionId()).isEqualTo(txId);
        assertThat(event.getPayload().getCardToken()).isEqualTo("vault:v1:tok");
        assertThat(event.getPayload().getOutcome()).isEqualTo("AUTHORIZED");
        assertThat(event.getPayload().getReasonCode()).isEqualTo("NONE");
    }
}
