package com.creditcard.booking.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BookingRoomNightTest {

    @Test
    void constructor_setsBookingIdRoomIdAndStayDate() {
        UUID bookingId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        LocalDate stayDate = LocalDate.of(2026, 11, 1);

        BookingRoomNight night = new BookingRoomNight(bookingId, roomId, stayDate);

        assertThat(night.getBookingId()).isEqualTo(bookingId);
        assertThat(night.getRoomId()).isEqualTo(roomId);
        assertThat(night.getStayDate()).isEqualTo(stayDate);
        assertThat(night.getId()).isNull();
    }

    @Test
    void noArgsConstructor() {
        BookingRoomNight night = new BookingRoomNight();

        assertThat(night.getBookingId()).isNull();
        assertThat(night.getRoomId()).isNull();
        assertThat(night.getStayDate()).isNull();
    }
}
