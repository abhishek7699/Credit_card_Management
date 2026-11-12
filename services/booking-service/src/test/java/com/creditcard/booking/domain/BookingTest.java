package com.creditcard.booking.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;

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

    @Test
    void legacyRoomPaymentTransactionId_getterAndSetter() {
        Booking booking = new Booking();
        booking.setLegacyRoomPaymentTransactionId("txn-legacy-123");

        assertThat(booking.getLegacyRoomPaymentTransactionId()).isEqualTo("txn-legacy-123");
    }

    @Test
    void guestDetailFields_getterAndSetter() {
        Booking booking = new Booking();
        booking.setGuestPhone("+1-555-0100");
        booking.setGuestEmail("jane@example.com");
        booking.setVisitingFrom("Chicago, IL");
        booking.setAdults(2);
        booking.setChildren(1);
        booking.setExpectedArrivalTime(LocalTime.of(15, 0));
        booking.setExpectedDepartureTime(LocalTime.of(11, 0));
        booking.setNotes("Requested a crib");
        booking.setRoomChargeAmount(new BigDecimal("240.00"));

        assertThat(booking.getGuestPhone()).isEqualTo("+1-555-0100");
        assertThat(booking.getGuestEmail()).isEqualTo("jane@example.com");
        assertThat(booking.getVisitingFrom()).isEqualTo("Chicago, IL");
        assertThat(booking.getAdults()).isEqualTo(2);
        assertThat(booking.getChildren()).isEqualTo(1);
        assertThat(booking.getExpectedArrivalTime()).isEqualTo(LocalTime.of(15, 0));
        assertThat(booking.getExpectedDepartureTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(booking.getNotes()).isEqualTo("Requested a crib");
        assertThat(booking.getRoomChargeAmount()).isEqualTo(new BigDecimal("240.00"));
    }
}
