package com.creditcard.booking.service;

import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.CancellationReason;
import com.creditcard.booking.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HoldExpiryServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingService bookingService;

    private HoldExpiryService service;

    @BeforeEach
    void setUp() {
        service = new HoldExpiryService(bookingRepository, bookingService, 10L);
    }

    private static Booking booking(UUID id) {
        Booking booking = new Booking();
        ReflectionTestUtils.setField(booking, "id", id);
        booking.setGuestName("Jane Doe");
        booking.setCheckIn(LocalDate.of(2026, 11, 1));
        booking.setCheckOut(LocalDate.of(2026, 11, 3));
        booking.setStatus(BookingStatus.HELD);
        return booking;
    }

    @Test
    void expireAbandonedHolds_cancelsEachExpiredBookingWithHoldExpiredReason() {
        Booking a = booking(UUID.randomUUID());
        Booking b = booking(UUID.randomUUID());
        when(bookingRepository.findByStatusAndCreatedAtBefore(eq(BookingStatus.HELD), any(Instant.class)))
                .thenReturn(List.of(a, b));

        service.expireAbandonedHolds();

        verify(bookingService).cancel(a, CancellationReason.HOLD_EXPIRED);
        verify(bookingService).cancel(b, CancellationReason.HOLD_EXPIRED);
    }

    @Test
    void expireAbandonedHolds_noExpiredBookings_cancelsNothing() {
        when(bookingRepository.findByStatusAndCreatedAtBefore(eq(BookingStatus.HELD), any(Instant.class)))
                .thenReturn(List.of());

        service.expireAbandonedHolds();

        verify(bookingService, never()).cancel(any(), any());
    }

    @Test
    void expireAbandonedHolds_oneBookingFailsToCancel_othersStillProcessed() {
        Booking failing = booking(UUID.randomUUID());
        Booking ok = booking(UUID.randomUUID());
        when(bookingRepository.findByStatusAndCreatedAtBefore(eq(BookingStatus.HELD), any(Instant.class)))
                .thenReturn(List.of(failing, ok));
        doThrow(new RuntimeException("db hiccup")).when(bookingService).cancel(failing, CancellationReason.HOLD_EXPIRED);

        service.expireAbandonedHolds();

        verify(bookingService, times(1)).cancel(failing, CancellationReason.HOLD_EXPIRED);
        verify(bookingService, times(1)).cancel(ok, CancellationReason.HOLD_EXPIRED);
    }
}
