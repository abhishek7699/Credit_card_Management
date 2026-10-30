package com.creditcard.booking.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutPaymentAttemptTest {

    @Test
    void onCreate_setsCreatedAtAndUpdatedAt() {
        CheckoutPaymentAttempt attempt = new CheckoutPaymentAttempt();
        attempt.onCreate();

        assertThat(attempt.getCreatedAt()).isNotNull();
        assertThat(attempt.getUpdatedAt()).isNotNull();
    }

    @Test
    void onUpdate_refreshesUpdatedAt() {
        CheckoutPaymentAttempt attempt = new CheckoutPaymentAttempt();
        attempt.onCreate();
        Instant createdAt = attempt.getCreatedAt();

        attempt.onUpdate();

        assertThat(attempt.getUpdatedAt()).isNotNull();
        assertThat(attempt.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    void gettersAndSetters() {
        CheckoutPaymentAttempt attempt = new CheckoutPaymentAttempt();
        UUID bookingId = UUID.randomUUID();

        attempt.setBookingId(bookingId);
        attempt.setTransactionId("txn-checkout-1");
        attempt.setAmount(new BigDecimal("42.00"));
        attempt.setStatus(CheckoutAttemptStatus.PENDING);

        assertThat(attempt.getBookingId()).isEqualTo(bookingId);
        assertThat(attempt.getTransactionId()).isEqualTo("txn-checkout-1");
        assertThat(attempt.getAmount()).isEqualByComparingTo(new BigDecimal("42.00"));
        assertThat(attempt.getStatus()).isEqualTo(CheckoutAttemptStatus.PENDING);
        assertThat(attempt.getId()).isNull();
    }
}
