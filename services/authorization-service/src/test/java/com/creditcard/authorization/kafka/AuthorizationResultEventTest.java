package com.creditcard.authorization.kafka;

import com.creditcard.authorization.domain.ReasonCode;
import com.creditcard.authorization.kafka.event.AuthorizationResultEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationResultEventTest {

    @Test
    void shouldCreateAuthorizedEvent() {
        UUID txId = UUID.randomUUID();
        AuthorizationResultEvent event = AuthorizationResultEvent.authorized(txId, "vault:v1:tok");

        assertThat(event.getEventType()).isEqualTo("TransactionAuthorized");
        assertThat(event.getTransactionId()).isEqualTo(txId);
        assertThat(event.getCardToken()).isEqualTo("vault:v1:tok");
        assertThat(event.getReasonCode()).isNull();
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getOccurredAt()).isNotNull();
    }

    @Test
    void shouldCreateDeclinedEvent() {
        UUID txId = UUID.randomUUID();
        AuthorizationResultEvent event = AuthorizationResultEvent.declined(txId, "vault:v1:tok", ReasonCode.INSUFFICIENT_CREDIT);

        assertThat(event.getEventType()).isEqualTo("TransactionDeclined");
        assertThat(event.getReasonCode()).isEqualTo(ReasonCode.INSUFFICIENT_CREDIT.name());
    }

    @Test
    void shouldCreateFailedEvent() {
        UUID txId = UUID.randomUUID();
        AuthorizationResultEvent event = AuthorizationResultEvent.failed(txId, "vault:v1:tok", ReasonCode.INTERNAL_ERROR);

        assertThat(event.getEventType()).isEqualTo("TransactionFailed");
        assertThat(event.getReasonCode()).isEqualTo(ReasonCode.INTERNAL_ERROR.name());
    }

    @Test
    void shouldSupportGettersAndSetters() {
        AuthorizationResultEvent event = new AuthorizationResultEvent();
        UUID txId = UUID.randomUUID();

        event.setEventId("id-1");
        event.setEventType("TransactionAuthorized");
        event.setTransactionId(txId);
        event.setCardToken("vault:v1:tok");
        event.setReasonCode(null);

        assertThat(event.getEventId()).isEqualTo("id-1");
        assertThat(event.getTransactionId()).isEqualTo(txId);
    }
}
