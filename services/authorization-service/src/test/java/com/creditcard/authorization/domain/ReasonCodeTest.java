package com.creditcard.authorization.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReasonCodeTest {

    @Test
    void shouldHaveFiveValues() {
        assertThat(ReasonCode.values()).hasSize(5);
    }

    @Test
    void shouldContainAllExpectedValues() {
        assertThat(ReasonCode.values()).containsExactlyInAnyOrder(
                ReasonCode.INVALID_CARD,
                ReasonCode.CARD_NOT_ACTIVE,
                ReasonCode.INSUFFICIENT_CREDIT,
                ReasonCode.INVALID_EVENT_PAYLOAD,
                ReasonCode.INTERNAL_ERROR
        );
    }
}
