package com.creditcard.authorization.contract;

import com.creditcard.authorization.domain.ReasonCode;
import com.creditcard.authorization.kafka.event.AuthorizationResultEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaEventSchemaTest {

    @Test
    void authorizedEventShouldHaveCorrectEventType() {
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
    void declinedEventShouldHaveCorrectEventTypeAndReasonCode() {
        UUID txId = UUID.randomUUID();
        AuthorizationResultEvent event = AuthorizationResultEvent.declined(txId, "vault:v1:tok", ReasonCode.INSUFFICIENT_CREDIT);

        assertThat(event.getEventType()).isEqualTo("TransactionDeclined");
        assertThat(event.getReasonCode()).isEqualTo("INSUFFICIENT_CREDIT");
        assertThat(event.getTransactionId()).isNotNull();
    }

    @Test
    void failedEventShouldHaveCorrectEventType() {
        UUID txId = UUID.randomUUID();
        AuthorizationResultEvent event = AuthorizationResultEvent.failed(txId, "vault:v1:tok", ReasonCode.INTERNAL_ERROR);

        assertThat(event.getEventType()).isEqualTo("TransactionFailed");
        assertThat(event.getReasonCode()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void allEventTypesShouldBeDistinct() {
        UUID txId = UUID.randomUUID();
        String authorized = AuthorizationResultEvent.authorized(txId, "tok").getEventType();
        String declined = AuthorizationResultEvent.declined(txId, "tok", ReasonCode.INVALID_CARD).getEventType();
        String failed = AuthorizationResultEvent.failed(txId, "tok", ReasonCode.INTERNAL_ERROR).getEventType();

        assertThat(authorized).isNotEqualTo(declined);
        assertThat(authorized).isNotEqualTo(failed);
        assertThat(declined).isNotEqualTo(failed);
    }
}
