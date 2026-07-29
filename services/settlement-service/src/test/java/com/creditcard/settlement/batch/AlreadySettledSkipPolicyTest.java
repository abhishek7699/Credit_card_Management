package com.creditcard.settlement.batch;

import com.creditcard.settlement.exception.AlreadySettledException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AlreadySettledSkipPolicyTest {

    private final AlreadySettledSkipPolicy policy = new AlreadySettledSkipPolicy();

    @Test
    void shouldSkip_alreadySettledException_returnsTrue() {
        assertThat(policy.shouldSkip(new AlreadySettledException("tx-001"), 0)).isTrue();
    }

    @Test
    void shouldSkip_runtimeException_returnsFalse() {
        assertThat(policy.shouldSkip(new RuntimeException("other"), 0)).isFalse();
    }

    @Test
    void shouldSkip_illegalStateException_returnsFalse() {
        assertThat(policy.shouldSkip(new IllegalStateException("state"), 999)).isFalse();
    }

    @Test
    void shouldSkip_alreadySettledExceptionHighSkipCount_returnsTrue() {
        assertThat(policy.shouldSkip(new AlreadySettledException("tx-999"), 999)).isTrue();
    }
}
