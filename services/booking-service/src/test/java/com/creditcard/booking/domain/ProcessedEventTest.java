package com.creditcard.booking.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessedEventTest {

    @Test
    void constructor_setsEventKeyAndProcessedAt() {
        ProcessedEvent event = new ProcessedEvent("booking:transaction.status.updated:evt-1");

        assertThat(event.getEventKey()).isEqualTo("booking:transaction.status.updated:evt-1");
        assertThat(event.getProcessedAt()).isNotNull();
    }

    @Test
    void noArgsConstructor() {
        ProcessedEvent event = new ProcessedEvent();

        assertThat(event.getEventKey()).isNull();
        assertThat(event.getProcessedAt()).isNull();
    }
}
