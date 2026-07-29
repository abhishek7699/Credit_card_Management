package com.creditcard.frauddetection.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FraudRuleConfigTest {

    @Test
    void shouldSetAndGetAllFields() {
        FraudRuleConfig config = new FraudRuleConfig();
        Instant now = Instant.now();

        config.setRuleType("VELOCITY_EXCEEDED");
        config.setEnabled(true);
        config.setParameters("{\"maxTransactions\": 5, \"windowMinutes\": 10}");
        config.setUpdatedAt(now);

        assertThat(config.getRuleType()).isEqualTo("VELOCITY_EXCEEDED");
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getParameters()).contains("maxTransactions");
        assertThat(config.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void shouldSupportDisabledRule() {
        FraudRuleConfig config = new FraudRuleConfig();
        config.setRuleType("BLACKLISTED_CARD");
        config.setEnabled(false);
        config.setParameters("{}");

        assertThat(config.isEnabled()).isFalse();
    }
}
