package com.creditcard.authorization.kafka;

import com.creditcard.authorization.kafka.event.TransactionAuthorizedEvent;
import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionAuthorizedEventTest {

    @Test
    void fromShouldCopyPayloadFieldsAndStampEnvelope() {
        UUID txId = UUID.randomUUID();
        Instant authorizedAt = Instant.parse("2026-06-13T23:45:00Z");

        TransactionInitiatedEvent.Payload src = new TransactionInitiatedEvent.Payload();
        src.setTransactionId(txId);
        src.setMerchantId("merchant-1");
        src.setCardToken("vault:v1:tok");
        src.setMaskedCardNumber("****-****-****-1111");
        src.setAmount(new BigDecimal("42.50"));
        src.setCurrencyCode("USD");

        TransactionAuthorizedEvent event = TransactionAuthorizedEvent.from(src, authorizedAt);

        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getEventType()).isEqualTo("TransactionAuthorized");
        assertThat(event.getSourceService()).isEqualTo("authorization-service");
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getPayload().getTransactionId()).isEqualTo(txId);
        assertThat(event.getPayload().getMerchantId()).isEqualTo("merchant-1");
        assertThat(event.getPayload().getCardToken()).isEqualTo("vault:v1:tok");
        assertThat(event.getPayload().getMaskedCardNumber()).isEqualTo("****-****-****-1111");
        assertThat(event.getPayload().getAmount()).isEqualByComparingTo("42.50");
        assertThat(event.getPayload().getCurrencyCode()).isEqualTo("USD");
        assertThat(event.getPayload().getAuthorizedAt()).isEqualTo(authorizedAt);
    }

    @Test
    void shouldSupportEnvelopeAndPayloadGettersAndSetters() {
        TransactionAuthorizedEvent event = new TransactionAuthorizedEvent();
        Instant now = Instant.now();
        event.setEventId("id-1");
        event.setEventType("TransactionAuthorized");
        event.setSourceService("authorization-service");
        event.setTraceId("trace-1");
        event.setOccurredAt(now);

        TransactionAuthorizedEvent.Payload payload = new TransactionAuthorizedEvent.Payload();
        UUID txId = UUID.randomUUID();
        Instant authAt = Instant.now();
        payload.setTransactionId(txId);
        payload.setMerchantId("m-2");
        payload.setCardToken("vault:v1:t");
        payload.setMaskedCardNumber("****-****-****-2222");
        payload.setAmount(new BigDecimal("10.00"));
        payload.setCurrencyCode("EUR");
        payload.setAuthorizedAt(authAt);
        event.setPayload(payload);

        assertThat(event.getEventId()).isEqualTo("id-1");
        assertThat(event.getEventType()).isEqualTo("TransactionAuthorized");
        assertThat(event.getSourceService()).isEqualTo("authorization-service");
        assertThat(event.getTraceId()).isEqualTo("trace-1");
        assertThat(event.getOccurredAt()).isEqualTo(now);
        assertThat(event.getPayload().getTransactionId()).isEqualTo(txId);
        assertThat(event.getPayload().getMerchantId()).isEqualTo("m-2");
        assertThat(event.getPayload().getCardToken()).isEqualTo("vault:v1:t");
        assertThat(event.getPayload().getMaskedCardNumber()).isEqualTo("****-****-****-2222");
        assertThat(event.getPayload().getAmount()).isEqualByComparingTo("10.00");
        assertThat(event.getPayload().getCurrencyCode()).isEqualTo("EUR");
        assertThat(event.getPayload().getAuthorizedAt()).isEqualTo(authAt);
    }
}
