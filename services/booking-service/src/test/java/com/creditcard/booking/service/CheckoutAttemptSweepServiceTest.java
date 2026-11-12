package com.creditcard.booking.service;

import com.creditcard.booking.domain.CheckoutAttemptStatus;
import com.creditcard.booking.domain.CheckoutPaymentAttempt;
import com.creditcard.booking.repository.CheckoutPaymentAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
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
class CheckoutAttemptSweepServiceTest {

    @Mock
    private CheckoutPaymentAttemptRepository checkoutPaymentAttemptRepository;
    @Mock
    private BookingService bookingService;

    private CheckoutAttemptSweepService service;

    @BeforeEach
    void setUp() {
        service = new CheckoutAttemptSweepService(checkoutPaymentAttemptRepository, bookingService, 10L);
    }

    private static CheckoutPaymentAttempt checkoutAttempt(UUID id) {
        CheckoutPaymentAttempt attempt = new CheckoutPaymentAttempt();
        ReflectionTestUtils.setField(attempt, "id", id);
        attempt.setBookingId(UUID.randomUUID());
        attempt.setAmount(java.math.BigDecimal.TEN);
        attempt.setStatus(CheckoutAttemptStatus.PENDING);
        return attempt;
    }

    // ── expireStuckCheckoutAttempts (layer 2c, FR-012a) ─────────────────────────

    @Test
    void expireStuckCheckoutAttempts_expiresEachStuckAttempt() {
        CheckoutPaymentAttempt a = checkoutAttempt(UUID.randomUUID());
        CheckoutPaymentAttempt b = checkoutAttempt(UUID.randomUUID());
        when(checkoutPaymentAttemptRepository.findByStatusAndCreatedAtBefore(eq(CheckoutAttemptStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(a, b));

        service.expireStuckCheckoutAttempts();

        verify(bookingService).expireCheckoutAttempt(a);
        verify(bookingService).expireCheckoutAttempt(b);
    }

    @Test
    void expireStuckCheckoutAttempts_noneStuck_expiresNothing() {
        when(checkoutPaymentAttemptRepository.findByStatusAndCreatedAtBefore(eq(CheckoutAttemptStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of());

        service.expireStuckCheckoutAttempts();

        verify(bookingService, never()).expireCheckoutAttempt(any());
    }

    @Test
    void expireStuckCheckoutAttempts_oneFailsToExpire_othersStillProcessed() {
        CheckoutPaymentAttempt failing = checkoutAttempt(UUID.randomUUID());
        CheckoutPaymentAttempt ok = checkoutAttempt(UUID.randomUUID());
        when(checkoutPaymentAttemptRepository.findByStatusAndCreatedAtBefore(eq(CheckoutAttemptStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(failing, ok));
        doThrow(new RuntimeException("db hiccup")).when(bookingService).expireCheckoutAttempt(failing);

        service.expireStuckCheckoutAttempts();

        verify(bookingService, times(1)).expireCheckoutAttempt(failing);
        verify(bookingService, times(1)).expireCheckoutAttempt(ok);
    }
}
