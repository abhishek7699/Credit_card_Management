package com.creditcard.booking.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BookingTest {

    @Test
    void onCreate_setsCreatedAtAndUpdatedAt() {
        Booking booking = new Booking();
        booking.onCreate();

        assertThat(booking.getCreatedAt()).isNotNull();
        assertThat(booking.getUpdatedAt()).isNotNull();
    }

    @Test
    void onUpdate_refreshesUpdatedAt() {
        Booking booking = new Booking();
        booking.onCreate();
        Instant createdUpdatedAt = booking.getUpdatedAt();

        booking.onUpdate();

        assertThat(booking.getUpdatedAt()).isNotNull();
        assertThat(booking.getCreatedAt()).isEqualTo(booking.getCreatedAt());
        assertThat(createdUpdatedAt).isNotNull();
    }

    @Test
    void cancellationReason_getterAndSetter() {
        Booking booking = new Booking();
        booking.setCancellationReason(CancellationReason.HOLD_EXPIRED);

        assertThat(booking.getCancellationReason()).isEqualTo(CancellationReason.HOLD_EXPIRED);
    }
}
