package com.creditcard.authorization.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationOutcomeTest {

    @Test
    void shouldHaveThreeValues() {
        assertThat(AuthorizationOutcome.values()).hasSize(3);
    }

    @Test
    void authorizedShouldBeApproved() {
        assertThat(AuthorizationOutcome.AUTHORIZED.isApproved()).isTrue();
    }

    @Test
    void declinedShouldNotBeApproved() {
        assertThat(AuthorizationOutcome.DECLINED.isApproved()).isFalse();
    }

    @Test
    void failedShouldNotBeApproved() {
        assertThat(AuthorizationOutcome.FAILED.isApproved()).isFalse();
    }
}
