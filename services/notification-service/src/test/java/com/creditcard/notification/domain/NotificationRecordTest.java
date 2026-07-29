package com.creditcard.notification.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationRecordTest {

    @Test
    void settersAndGettersRoundTrip() {
        NotificationRecord r = new NotificationRecord();
        r.setEventId("evt-001");
        r.setEventType("TransactionAuthorized");
        r.setChannel("EMAIL");
        r.setRecipientMasked("t***@example.com");
        r.setContent("Payment approved: Card ending in 1234.");
        r.setStatus("SENT");
        r.setFailureReason(null);
        r.setSentAt(Instant.now());

        assertThat(r.getEventId()).isEqualTo("evt-001");
        assertThat(r.getEventType()).isEqualTo("TransactionAuthorized");
        assertThat(r.getChannel()).isEqualTo("EMAIL");
        assertThat(r.getRecipientMasked()).isEqualTo("t***@example.com");
        assertThat(r.getContent()).contains("1234");
        assertThat(r.getStatus()).isEqualTo("SENT");
        assertThat(r.getFailureReason()).isNull();
        assertThat(r.getSentAt()).isNotNull();
        assertThat(r.getId()).isNull();
        assertThat(r.getCreatedAt()).isNull();
    }

    @Test
    void defaultStatusIsPending() {
        NotificationRecord r = new NotificationRecord();
        assertThat(r.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void failureReasonCanBeSet() {
        NotificationRecord r = new NotificationRecord();
        r.setFailureReason("SMTP connection refused");
        assertThat(r.getFailureReason()).isEqualTo("SMTP connection refused");
    }
}
