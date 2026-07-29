package com.creditcard.notification.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessedEventTest {

    @Test
    void allArgsConstructorSetsEventKeyAndProcessedAt() {
        ProcessedEvent event = new ProcessedEvent("notif:TransactionAuthorized:abc");
        assertThat(event.getEventKey()).isEqualTo("notif:TransactionAuthorized:abc");
        assertThat(event.getProcessedAt()).isNotNull();
    }

    @Test
    void noArgsConstructorAndSetters() {
        ProcessedEvent event = new ProcessedEvent();
        event.setEventKey("notif:FraudAlertRaised:xyz");
        assertThat(event.getEventKey()).isEqualTo("notif:FraudAlertRaised:xyz");
        assertThat(event.getProcessedAt()).isNull();
    }
}
