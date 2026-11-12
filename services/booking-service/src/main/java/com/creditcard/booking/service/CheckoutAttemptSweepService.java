package com.creditcard.booking.service;

import com.creditcard.booking.domain.CheckoutAttemptStatus;
import com.creditcard.booking.domain.CheckoutPaymentAttempt;
import com.creditcard.booking.repository.CheckoutPaymentAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * FR-012a (Clarifications): sweeps stuck checkout attempts that received no outcome
 * within the configured window (default 10 minutes), reverting their booking to
 * checked in so staff can retry. Polls on a short fixed delay rather than scheduling
 * individual per-attempt timers (research.md §5) — a plain age-based query is the
 * simplest thing that is still correct at this layer's scale.
 *
 * <p>Named {@code CheckoutAttemptSweepService}, not {@code HoldExpiryService} (its
 * pre-2026-10-31-rework name): this class used to also auto-cancel an abandoned HELD
 * booking awaiting a booking-time payment outcome. That concept no longer exists — a
 * booking is confirmed synchronously, with no HELD state to expire — so this class now
 * does only the one thing its current name describes (research.md §8).
 */
@Service
public class CheckoutAttemptSweepService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutAttemptSweepService.class);
    private static final long POLL_INTERVAL_MS = 30_000L;

    private final CheckoutPaymentAttemptRepository checkoutPaymentAttemptRepository;
    private final BookingService bookingService;
    private final long checkoutTimeoutMinutes;

    public CheckoutAttemptSweepService(CheckoutPaymentAttemptRepository checkoutPaymentAttemptRepository,
                                        BookingService bookingService,
                                        @Value("${booking.checkout-timeout-minutes:10}") long checkoutTimeoutMinutes) {
        this.checkoutPaymentAttemptRepository = checkoutPaymentAttemptRepository;
        this.bookingService = bookingService;
        this.checkoutTimeoutMinutes = checkoutTimeoutMinutes;
    }

    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void expireStuckCheckoutAttempts() {
        Instant cutoff = Instant.now().minus(checkoutTimeoutMinutes, ChronoUnit.MINUTES);
        List<CheckoutPaymentAttempt> stuck = checkoutPaymentAttemptRepository
                .findByStatusAndCreatedAtBefore(CheckoutAttemptStatus.PENDING, cutoff);
        for (CheckoutPaymentAttempt attempt : stuck) {
            // Each attempt expires in its own transaction (BookingService#expireCheckoutAttempt
            // is @Transactional) so one failing row never blocks the rest of the batch.
            try {
                bookingService.expireCheckoutAttempt(attempt);
            } catch (RuntimeException e) {
                log.error("Failed to expire stuck checkout attempt {}: {}", attempt.getId(), e.getMessage(), e);
            }
        }
    }
}
