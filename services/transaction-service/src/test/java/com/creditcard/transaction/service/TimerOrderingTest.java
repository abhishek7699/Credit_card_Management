package com.creditcard.transaction.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 013 timer ordering. Values are seconds: P, I, K, M, b, m. R = 2M + b. */
class TimerOrderingTest {

    @Test
    void acceptsTheOwnerValues() {
        // P = 300, I = 30, K = 60, M = 10, b = 2 (R = 22), m = 60
        assertThatCode(() -> TimerOrdering.checkTransactionRules(300, 30, 60, 10, 2, 60))
                .doesNotThrowAnyException();
    }

    @Test
    void rule1AcceptsTheExactBoundaryAndRejectsOneSecondLess() {
        // K + M + R + m = 60 + 10 + 22 + 60 = 152
        assertThatCode(() -> TimerOrdering.checkTransactionRules(152, 30, 60, 10, 2, 60))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> TimerOrdering.checkTransactionRules(151, 30, 60, 10, 2, 60))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rule 1");
    }

    @Test
    void rule3RejectsASweepIntervalLongerThanTheTimeout() {
        assertThatCode(() -> TimerOrdering.checkTransactionRules(300, 300, 60, 10, 2, 60))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> TimerOrdering.checkTransactionRules(300, 301, 60, 10, 2, 60))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rule 3");
    }

    @Test
    void rule2AcceptsTheBoundaryAndRejectsOneSecondMore() {
        // P + m + d = 300 + 60 + 5 = 365 against S = 600 (the booking checkout window)
        assertThatCode(() -> TimerOrdering.checkBookingWindow(300, 60, 5, 600)).doesNotThrowAnyException();
        assertThatCode(() -> TimerOrdering.checkBookingWindow(300, 60, 5, 365)).doesNotThrowAnyException();
        assertThatThrownBy(() -> TimerOrdering.checkBookingWindow(300, 60, 5, 364))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rule 2");
    }

    @Test
    void startupConstructorAppliesTheRulesToConfiguredValues() {
        assertThatCode(() -> new TimerOrdering(300, 30_000, 60, 10, 2, 60)).doesNotThrowAnyException();
        // The sweep interval is configured in milliseconds: 301 s > P = 300 s breaks rule 3.
        assertThatThrownBy(() -> new TimerOrdering(300, 301_000, 60, 10, 2, 60))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rule 3");
        assertThatThrownBy(() -> new TimerOrdering(100, 30_000, 60, 10, 2, 60))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rule 1");
    }
}
