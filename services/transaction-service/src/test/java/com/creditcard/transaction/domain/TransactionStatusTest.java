package com.creditcard.transaction.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionStatusTest {

    @Test
    void shouldDefineAllFourStatuses() {
        assertThat(TransactionStatus.values()).containsExactlyInAnyOrder(
                TransactionStatus.PENDING,
                TransactionStatus.AUTHORIZED,
                TransactionStatus.DECLINED,
                TransactionStatus.FAILED
        );
    }

    @Test
    void pendingShouldNotBeTerminal() {
        assertThat(TransactionStatus.PENDING.isTerminal()).isFalse();
    }

    @Test
    void authorizedShouldBeTerminal() {
        assertThat(TransactionStatus.AUTHORIZED.isTerminal()).isTrue();
    }

    @Test
    void declinedShouldBeTerminal() {
        assertThat(TransactionStatus.DECLINED.isTerminal()).isTrue();
    }

    @Test
    void failedShouldBeTerminal() {
        assertThat(TransactionStatus.FAILED.isTerminal()).isTrue();
    }
}
