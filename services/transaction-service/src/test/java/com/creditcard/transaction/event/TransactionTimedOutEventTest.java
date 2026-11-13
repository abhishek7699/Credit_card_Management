package com.creditcard.transaction.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionTimedOutEventTest {

    @Test
    void roundTripsTheEnvelopeAndPayloadThroughJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        TransactionTimedOutEvent event = new TransactionTimedOutEvent();
        event.setEventId("00000000-0000-0000-0000-000000000001");
        event.setEventType("TransactionTimedOut");
        event.setSourceService("transaction-service");
        event.setTraceId("trace-1");
        event.setOccurredAt(Instant.parse("2026-11-12T12:00:00Z"));
        TransactionTimedOutEvent.Payload payload = new TransactionTimedOutEvent.Payload();
        payload.setTransactionId("tx-1");
        payload.setCardToken("vault:v1:token");
        event.setPayload(payload);

        TransactionTimedOutEvent back = mapper.readValue(mapper.writeValueAsString(event), TransactionTimedOutEvent.class);

        assertThat(back.getEventId()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(back.getEventType()).isEqualTo("TransactionTimedOut");
        assertThat(back.getSourceService()).isEqualTo("transaction-service");
        assertThat(back.getTraceId()).isEqualTo("trace-1");
        assertThat(back.getOccurredAt()).isEqualTo(Instant.parse("2026-11-12T12:00:00Z"));
        assertThat(back.getPayload().getTransactionId()).isEqualTo("tx-1");
        assertThat(back.getPayload().getCardToken()).isEqualTo("vault:v1:token");
    }
}
