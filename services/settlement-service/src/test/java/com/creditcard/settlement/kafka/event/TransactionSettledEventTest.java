package com.creditcard.settlement.kafka.event;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionSettledEventTest {

    @Test
    void defaultConstructor_setsEventIdAndOccurredAt() {
        TransactionSettledEvent event = new TransactionSettledEvent();
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getEventType()).isEqualTo("TransactionSettled");
        assertThat(event.getSourceService()).isEqualTo("settlement-service");
    }

    @Test
    void setEventId_overridesDefault() {
        TransactionSettledEvent event = new TransactionSettledEvent();
        event.setEventId("fixed-id");
        assertThat(event.getEventId()).isEqualTo("fixed-id");
    }

    @Test
    void setEventType_overridesDefault() {
        TransactionSettledEvent event = new TransactionSettledEvent();
        event.setEventType("CustomType");
        assertThat(event.getEventType()).isEqualTo("CustomType");
    }

    @Test
    void setSourceService_overridesDefault() {
        TransactionSettledEvent event = new TransactionSettledEvent();
        event.setSourceService("other-service");
        assertThat(event.getSourceService()).isEqualTo("other-service");
    }

    @Test
    void setTraceId_roundtrip() {
        TransactionSettledEvent event = new TransactionSettledEvent();
        event.setTraceId("trace-xyz");
        assertThat(event.getTraceId()).isEqualTo("trace-xyz");
    }

    @Test
    void setOccurredAt_overridesDefault() {
        TransactionSettledEvent event = new TransactionSettledEvent();
        Instant fixed = Instant.parse("2026-01-01T00:00:00Z");
        event.setOccurredAt(fixed);
        assertThat(event.getOccurredAt()).isEqualTo(fixed);
    }

    @Test
    void setPayload_roundtrip() {
        TransactionSettledEvent event = new TransactionSettledEvent();
        TransactionSettledEvent.Payload payload = new TransactionSettledEvent.Payload();
        event.setPayload(payload);
        assertThat(event.getPayload()).isSameAs(payload);
    }

    @Test
    void payload_allGettersAndSetters() {
        UUID txId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 1, 15);
        Instant now = Instant.now();

        TransactionSettledEvent.Payload payload = new TransactionSettledEvent.Payload();
        payload.setTransactionId(txId);
        payload.setMerchantId("merchant-A");
        payload.setBatchId("batch-001");
        payload.setGrossAmount(new BigDecimal("200.00"));
        payload.setInterchangeFee(new BigDecimal("4.00"));
        payload.setNetAmount(new BigDecimal("196.00"));
        payload.setCurrencyCode("USD");
        payload.setSettlementDate(date);
        payload.setSettledAt(now);

        assertThat(payload.getTransactionId()).isEqualTo(txId);
        assertThat(payload.getMerchantId()).isEqualTo("merchant-A");
        assertThat(payload.getBatchId()).isEqualTo("batch-001");
        assertThat(payload.getGrossAmount()).isEqualByComparingTo("200.00");
        assertThat(payload.getInterchangeFee()).isEqualByComparingTo("4.00");
        assertThat(payload.getNetAmount()).isEqualByComparingTo("196.00");
        assertThat(payload.getCurrencyCode()).isEqualTo("USD");
        assertThat(payload.getSettlementDate()).isEqualTo(date);
        assertThat(payload.getSettledAt()).isEqualTo(now);
    }
}
