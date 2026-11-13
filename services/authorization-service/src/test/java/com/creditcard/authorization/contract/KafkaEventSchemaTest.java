package com.creditcard.authorization.contract;

import com.creditcard.authorization.domain.ReasonCode;
import com.creditcard.authorization.kafka.event.AuthorizationResultEvent;
import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaEventSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void authorizedResultEventShouldSerializeAsEnvelopeWithPayload() throws Exception {
        UUID txId = UUID.randomUUID();
        AuthorizationResultEvent event = AuthorizationResultEvent.authorized(txId, "vault:v1:tok");

        JsonNode json = mapper.readTree(mapper.writeValueAsString(event));

        assertThat(json.get("eventType").asText()).isEqualTo("TransactionAuthorized");
        assertThat(json.get("sourceService").asText()).isEqualTo("authorization-service");
        assertThat(json.get("eventId").asText()).isNotBlank();
        assertThat(json.get("payload").get("transactionId").asText()).isEqualTo(txId.toString());
        assertThat(json.get("payload").get("outcome").asText()).isEqualTo("AUTHORIZED");
        assertThat(json.get("payload").get("reasonCode").isNull()).isTrue();
    }

    @Test
    void declinedEventShouldCarryReasonCodeInPayload() {
        AuthorizationResultEvent event = AuthorizationResultEvent.declined(
                UUID.randomUUID(), "vault:v1:tok", ReasonCode.INSUFFICIENT_CREDIT);

        assertThat(event.getEventType()).isEqualTo("TransactionDeclined");
        assertThat(event.getPayload().getReasonCode()).isEqualTo("INSUFFICIENT_CREDIT");
        assertThat(event.getPayload().getTransactionId()).isNotNull();
    }

    @Test
    void failedEventShouldCarryReasonCodeInPayload() {
        AuthorizationResultEvent event = AuthorizationResultEvent.failed(
                UUID.randomUUID(), "vault:v1:tok", ReasonCode.INTERNAL_ERROR);

        assertThat(event.getEventType()).isEqualTo("TransactionFailed");
        assertThat(event.getPayload().getReasonCode()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void allResultEventTypesShouldBeDistinct() {
        UUID txId = UUID.randomUUID();
        String authorized = AuthorizationResultEvent.authorized(txId, "tok").getEventType();
        String declined = AuthorizationResultEvent.declined(txId, "tok", ReasonCode.INVALID_CARD).getEventType();
        String failed = AuthorizationResultEvent.failed(txId, "tok", ReasonCode.INTERNAL_ERROR).getEventType();

        assertThat(authorized).isNotEqualTo(declined);
        assertThat(authorized).isNotEqualTo(failed);
        assertThat(declined).isNotEqualTo(failed);
    }

}
