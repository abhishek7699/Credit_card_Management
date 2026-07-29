package com.creditcard.authorization.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessedEventTest {

    @Test
    void shouldSetAndGetAllFields() {
        ProcessedEvent event = new ProcessedEvent();
        Instant now = Instant.now();

        event.setEventKey("auth:TransactionInitiated:evt-001");
        event.setProcessedAt(now);

        assertThat(event.getEventKey()).isEqualTo("auth:TransactionInitiated:evt-001");
        assertThat(event.getProcessedAt()).isEqualTo(now);
    }
}
