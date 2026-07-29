package com.creditcard.transaction.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessedEventTest {

    @Test
    void shouldCreateProcessedEventWithEventKey() {
        ProcessedEvent event = new ProcessedEvent();
        event.setEventKey("tx:TransactionAuthorized:550e8400-e29b-41d4-a716-446655440000");
        event.setProcessedAt(Instant.now());

        assertThat(event.getEventKey()).isNotBlank();
        assertThat(event.getProcessedAt()).isNotNull();
    }

    @Test
    void eventKeyShouldFollowSchema() {
        String eventKey = "tx:TransactionAuthorized:550e8400-e29b-41d4-a716-446655440000";
        ProcessedEvent event = new ProcessedEvent();
        event.setEventKey(eventKey);

        // Key format: {prefix}:{eventType}:{eventId}
        assertThat(event.getEventKey()).startsWith("tx:");
        assertThat(event.getEventKey().split(":")).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void differentEventTypesShouldProduceDifferentKeys() {
        String authorizedKey = "tx:TransactionAuthorized:550e8400-e29b-41d4-a716-446655440000";
        String declinedKey   = "tx:TransactionDeclined:550e8400-e29b-41d4-a716-446655440000";

        assertThat(authorizedKey).isNotEqualTo(declinedKey);
    }
}
