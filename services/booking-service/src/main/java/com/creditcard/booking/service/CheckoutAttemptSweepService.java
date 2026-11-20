package com.creditcard.booking.service;

import com.creditcard.booking.domain.CheckoutAttemptStatus;
import com.creditcard.booking.domain.CheckoutPaymentAttempt;
import com.creditcard.booking.domain.UnmatchedPaymentOutcome;
import com.creditcard.booking.repository.CheckoutPaymentAttemptRepository;
import com.creditcard.transaction.grpc.v1.LookupTransactionByIdempotencyKeyRequest;
import com.creditcard.transaction.grpc.v1.LookupTransactionByIdempotencyKeyResponse;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    private final CheckoutPaymentLedger checkoutPaymentLedger;
    private final TransactionServiceGrpc.TransactionServiceBlockingStub transactionServiceBlockingStub;
    private final long checkoutTimeoutMinutes;
    private final Duration unmatchedAlertAfter;
    private final AtomicLong staleUnmatchedOutcomes = new AtomicLong();

    public CheckoutAttemptSweepService(CheckoutPaymentAttemptRepository checkoutPaymentAttemptRepository,
                                       BookingService bookingService,
                                       CheckoutPaymentLedger checkoutPaymentLedger,
                                       TransactionServiceGrpc.TransactionServiceBlockingStub transactionServiceBlockingStub,
                                       MeterRegistry meterRegistry,
                                       @Value("${booking.checkout-timeout-minutes:10}") long checkoutTimeoutMinutes,
                                       @Value("${booking.checkout.unmatched-alert-after:PT1H}") Duration unmatchedAlertAfter) {
        this.checkoutPaymentAttemptRepository = checkoutPaymentAttemptRepository;
        this.bookingService = bookingService;
        this.checkoutPaymentLedger = checkoutPaymentLedger;
        this.transactionServiceBlockingStub = transactionServiceBlockingStub;
        this.checkoutTimeoutMinutes = checkoutTimeoutMinutes;
        this.unmatchedAlertAfter = unmatchedAlertAfter;
        Gauge.builder("booking.checkout.unmatched_outcomes.stale", staleUnmatchedOutcomes, AtomicLong::get)
                .description("Parked transaction outcomes with no checkout attempt linked past the alert threshold")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void expireStuckCheckoutAttempts() {
        Instant now = Instant.now();
        List<CheckoutPaymentAttempt> stuck = checkoutPaymentAttemptRepository
                .findByStatusAndCreatedAtBefore(CheckoutAttemptStatus.PENDING,
                        now.minus(checkoutTimeoutMinutes, ChronoUnit.MINUTES));
        for (CheckoutPaymentAttempt attempt : stuck) {
            resolveOrExpire(attempt);
        }
        for (UnmatchedPaymentOutcome parked : checkoutPaymentLedger.allParked()) {
            try {
                checkoutPaymentLedger.applyParked(parked.getTransactionId());
            } catch (RuntimeException e) {
                log.error("Failed to apply parked outcome for transaction {}: {}",
                        parked.getTransactionId(), e.getMessage(), e);
            }
        }
        reportStaleUnmatchedOutcomes(now);
    }

    /**
     * Asks transaction-service, by the attempt's key, what happened. This is lookup only and never
     * sends InitiateTransaction. A terminal outcome is applied. No record means nothing was charged,
     * so the attempt expires and the booking returns to CHECKED_IN. A failed lookup leaves the attempt
     * PENDING for the next pass rather than guessing.
     */
    private void resolveOrExpire(CheckoutPaymentAttempt attempt) {
        String key = attempt.getId().toString();
        try {
            Optional<LookupTransactionByIdempotencyKeyResponse> found = lookup(key);
            Optional<String> outcome = found.map(r -> terminalOutcome(r.getStatus()));
            if (found.isPresent() && outcome.isPresent()) {
                checkoutPaymentLedger.resolveStuckAttempt(attempt.getId(), found.get().getTransactionId(),
                        outcome.get());
            } else {
                bookingService.expireCheckoutAttempt(attempt);
            }
        } catch (StatusRuntimeException e) {
            log.warn("Lookup by key failed for checkout attempt {} ({}); left PENDING for the next pass",
                    key, e.getStatus().getCode());
        } catch (RuntimeException e) {
            log.error("Failed to resolve stuck checkout attempt {}: {}", key, e.getMessage(), e);
        }
    }

    private Optional<LookupTransactionByIdempotencyKeyResponse> lookup(String key) {
        LookupTransactionByIdempotencyKeyResponse response = transactionServiceBlockingStub
                .withWaitForReady()
                .withDeadlineAfter(5, TimeUnit.SECONDS)
                .lookupTransactionByIdempotencyKey(LookupTransactionByIdempotencyKeyRequest.newBuilder()
                        .setIdempotencyKey(key)
                        .build());
        return response.getFound() ? Optional.of(response) : Optional.empty();
    }

    /** The outcome to apply for a transaction status, or null while the transaction is still PENDING. */
    private static String terminalOutcome(String status) {
        return switch (status) {
            case "AUTHORIZED", "DECLINED", "FAILED" -> status;
            default -> null;
        };
    }

    /** Sets the stale gauge and logs each parked outcome still unmatched past the threshold. Rows are never deleted. */
    private void reportStaleUnmatchedOutcomes(Instant now) {
        List<UnmatchedPaymentOutcome> stale = checkoutPaymentLedger.parkedBefore(now.minus(unmatchedAlertAfter));
        staleUnmatchedOutcomes.set(stale.size());
        for (UnmatchedPaymentOutcome row : stale) {
            log.error("Payment outcome {} for transaction {} (event {}, received {}) has no checkout attempt linked "
                            + "after {} — kept, not deleted; needs investigation",
                    row.getOutcome(), row.getTransactionId(), row.getEventId(), row.getReceivedAt(), unmatchedAlertAfter);
        }
    }
}
