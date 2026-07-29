package com.creditcard.authorization.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CardAccountStatusTest {

    @Test
    void shouldHaveThreeValues() {
        assertThat(CardAccountStatus.values()).hasSize(3);
    }

    @Test
    void activeShouldBeActive() {
        assertThat(CardAccountStatus.ACTIVE.isActive()).isTrue();
    }

    @Test
    void blockedShouldNotBeActive() {
        assertThat(CardAccountStatus.BLOCKED.isActive()).isFalse();
    }

    @Test
    void expiredShouldNotBeActive() {
        assertThat(CardAccountStatus.EXPIRED.isActive()).isFalse();
    }
}
