package com.creditcard.frauddetection.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BlacklistEntryTest {

    @Test
    void shouldSetAndGetAllFields() {
        BlacklistEntry entry = new BlacklistEntry();
        Instant now = Instant.now();

        entry.setCardToken("vault:v1:blocked-tok");
        entry.setReason("Reported stolen");
        entry.setAddedAt(now);

        assertThat(entry.getCardToken()).isEqualTo("vault:v1:blocked-tok");
        assertThat(entry.getReason()).isEqualTo("Reported stolen");
        assertThat(entry.getAddedAt()).isEqualTo(now);
    }

    @Test
    void shouldAllowNullReason() {
        BlacklistEntry entry = new BlacklistEntry();
        entry.setCardToken("vault:v1:tok");
        entry.setReason(null);

        assertThat(entry.getReason()).isNull();
    }
}
