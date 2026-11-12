package com.creditcard.authorization.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationOutcomeTest {

    @Test
    void shouldHaveFourValuesIncludingReleased() {
        assertThat(AuthorizationOutcome.values()).hasSize(4);
    }

    @Test
    void releasedShouldNotBeApproved() {
        assertThat(AuthorizationOutcome.RELEASED.isApproved()).isFalse();
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
