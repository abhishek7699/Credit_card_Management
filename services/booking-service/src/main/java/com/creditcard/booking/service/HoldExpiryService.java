package com.creditcard.booking.service;

import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.CancellationReason;
import com.creditcard.booking.domain.CheckoutAttemptStatus;
import com.creditcard.booking.domain.CheckoutPaymentAttempt;
import com.creditcard.booking.repository.BookingRepository;
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
 * FR-015: auto-cancels any booking that has remained HELD longer than the configured
 * timeout (default 10 minutes), releasing the room. Polls on a short fixed delay rather
 * than scheduling individual per-booking timers (research.md §5) — a plain age-based
 * query is the simplest thing that is still correct at this layer's scale.
 *
 * <p>FR-012a (layer 2c, Clarifications): the same mechanism, same config value, also
 * sweeps stuck checkout attempts — "one scheduled mechanism, not two to keep in sync"
 * (plan.md Key Design Decision #4), rather than a second class with its own timeout.
 */
@Service
public class HoldExpiryService {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryService.class);
    private static final long POLL_INTERVAL_MS = 30_000L;

    private final BookingRepository bookingRepository;
    private final CheckoutPaymentAttemptRepository checkoutPaymentAttemptRepository;
    private final BookingService bookingService;
    private final long holdTimeoutMinutes;

    public HoldExpiryService(BookingRepository bookingRepository,
                              CheckoutPaymentAttemptRepository checkoutPaymentAttemptRepository,
                              BookingService bookingService,
                              @Value("${booking.hold-timeout-minutes:10}") long holdTimeoutMinutes) {
        this.bookingRepository = bookingRepository;
        this.checkoutPaymentAttemptRepository = checkoutPaymentAttemptRepository;
        this.bookingService = bookingService;
        this.holdTimeoutMinutes = holdTimeoutMinutes;
    }

    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void expireAbandonedHolds() {
        Instant cutoff = Instant.now().minus(holdTimeoutMinutes, ChronoUnit.MINUTES);
        List<Booking> expired = bookingRepository.findByStatusAndCreatedAtBefore(BookingStatus.HELD, cutoff);
        for (Booking booking : expired) {
            // Each booking cancels in its own transaction (BookingService#cancel is
            // @Transactional) so one failing row never blocks the rest of the batch.
            try {
                bookingService.cancel(booking, CancellationReason.HOLD_EXPIRED);
            } catch (RuntimeException e) {
                log.error("Failed to expire HELD booking {}: {}", booking.getId(), e.getMessage(), e);
            }
        }
    }

    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void expireStuckCheckoutAttempts() {
        Instant cutoff = Instant.now().minus(holdTimeoutMinutes, ChronoUnit.MINUTES);
        List<CheckoutPaymentAttempt> stuck = checkoutPaymentAttemptRepository
                .findByStatusAndCreatedAtBefore(CheckoutAttemptStatus.PENDING, cutoff);
        for (CheckoutPaymentAttempt attempt : stuck) {
            // Each attempt expires in its own transaction (BookingService#expireCheckoutAttempt
            // is @Transactional), same isolation as expireAbandonedHolds above.
            try {
                bookingService.expireCheckoutAttempt(attempt);
            } catch (RuntimeException e) {
                log.error("Failed to expire stuck checkout attempt {}: {}", attempt.getId(), e.getMessage(), e);
            }
        }
    }
}
