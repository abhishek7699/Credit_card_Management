package com.creditcard.settlement.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessedEventTest {

    @Test
    void allArgsConstructor_setsKeyAndProcessedAt() {
        ProcessedEvent event = new ProcessedEvent("settlement:TransactionAuthorized:evt-001");
        assertThat(event.getEventKey()).isEqualTo("settlement:TransactionAuthorized:evt-001");
        assertThat(event.getProcessedAt()).isNotNull();
    }

    @Test
    void noArgsConstructor_fieldsNull() {
        ProcessedEvent event = new ProcessedEvent();
        assertThat(event.getEventKey()).isNull();
        assertThat(event.getProcessedAt()).isNull();
    }

    @Test
    void setters_overrideValues() {
        ProcessedEvent event = new ProcessedEvent("key");
        event.setEventKey("new-key");
        assertThat(event.getEventKey()).isEqualTo("new-key");
    }

    @Test
    void setProcessedAt_overridesValue() {
        ProcessedEvent event = new ProcessedEvent("key");
        Instant fixed = Instant.parse("2026-01-01T00:00:00Z");
        event.setProcessedAt(fixed);
        assertThat(event.getProcessedAt()).isEqualTo(fixed);
    }
}
