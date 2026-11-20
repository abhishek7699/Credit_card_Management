package com.creditcard.booking.service;

import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.CheckoutAttemptStatus;
import com.creditcard.booking.domain.CheckoutPaymentAttempt;
import com.creditcard.booking.domain.RoomStatus;
import com.creditcard.booking.domain.UnmatchedPaymentOutcome;
import com.creditcard.booking.exception.BookingNotFoundException;
import com.creditcard.booking.exception.InvalidBookingStateException;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.CheckoutPaymentAttemptRepository;
import com.creditcard.booking.repository.RoomRepository;
import com.creditcard.booking.repository.UnmatchedPaymentOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every write to a checkout attempt and to its booking, room and parked outcomes. Each public
 * method is its own transaction when called from outside (BookingService, the sweep). That is what
 * lets a checkout attempt commit before the payment call goes out, and an outcome be parked or
 * applied without any chance of being dropped.
 */
@Service
public class CheckoutPaymentLedger {

    private static final Logger log = LoggerFactory.getLogger(CheckoutPaymentLedger.class);
    static final String OUTCOME_AUTHORIZED = "AUTHORIZED";
    static final String OUTCOME_DECLINED = "DECLINED";
    static final String OUTCOME_FAILED = "FAILED";

    private final BookingRepository bookingRepository;
    private final RoomRepository roomRepository;
    private final CheckoutPaymentAttemptRepository checkoutPaymentAttemptRepository;
    private final UnmatchedPaymentOutcomeRepository unmatchedOutcomes;

    public CheckoutPaymentLedger(BookingRepository bookingRepository,
                                 RoomRepository roomRepository,
                                 CheckoutPaymentAttemptRepository checkoutPaymentAttemptRepository,
                                 UnmatchedPaymentOutcomeRepository unmatchedOutcomes) {
        this.bookingRepository = bookingRepository;
        this.roomRepository = roomRepository;
        this.checkoutPaymentAttemptRepository = checkoutPaymentAttemptRepository;
        this.unmatchedOutcomes = unmatchedOutcomes;
    }

    /**
     * Moves the booking to CHECKOUT_PENDING and saves a PENDING attempt for {@code amount}, in one
     * committed transaction. This runs before any payment call, so a crash during the call still
     * leaves the attempt in the database, keyed by its id.
     */
    @Transactional
    public CheckoutPaymentAttempt begin(UUID bookingId, BigDecimal amount) {
        int bookingUpdated = bookingRepository.updateStatusIfCurrent(
                bookingId, BookingStatus.CHECKOUT_PENDING, BookingStatus.CHECKED_IN);
        if (bookingUpdated == 0) {
            throw new InvalidBookingStateException(bookingId,
                    "booking is no longer CHECKED_IN (a checkout attempt may already be in progress)");
        }
        CheckoutPaymentAttempt attempt = new CheckoutPaymentAttempt();
        attempt.setBookingId(bookingId);
        attempt.setAmount(amount);
        attempt.setStatus(CheckoutAttemptStatus.PENDING);
        return checkoutPaymentAttemptRepository.save(attempt);
    }

    /** Zero balance: the booking completes at once, with no payment call. */
    @Transactional
    public void completeWithNoBalance(Booking booking) {
        int bookingUpdated = bookingRepository.updateStatusIfCurrent(
                booking.getId(), BookingStatus.CHECKED_OUT, BookingStatus.CHECKED_IN);
        if (bookingUpdated == 0) {
            throw new InvalidBookingStateException(booking.getId(), "booking is no longer CHECKED_IN");
        }
        roomRepository.setStatus(booking.getRoomId(), RoomStatus.DIRTY);
    }

    /** Records the transactionId an attempt was given. Only the first value is kept. */
    @Transactional
    public void linkTransaction(UUID attemptId, String transactionId) {
        checkoutPaymentAttemptRepository.linkTransactionIfAbsent(attemptId, transactionId);
    }

    /**
     * FR-010/FR-011 (checkout saga): PENDING+AUTHORIZED -> attempt AUTHORIZED, booking
     * CHECKOUT_PENDING -> CHECKED_OUT, room -> DIRTY; PENDING+DECLINED/FAILED -> attempt
     * DECLINED/FAILED, booking back to CHECKED_IN. FR-012b: EXPIRED+AUTHORIZED is recorded as
     * LATE_AUTHORIZED (it reduces the balance), the booking stays CHECKED_IN, and a WARN is logged.
     * FR-012c: EXPIRED+DECLINED/FAILED is a no-op. Each transition is conditional on the attempt's
     * current status, so a concurrent sweep and a late outcome never both apply.
     */
    @Transactional
    public void applyOutcome(CheckoutPaymentAttempt attempt, String outcome) {
        if (attempt.getStatus() == CheckoutAttemptStatus.PENDING) {
            if (OUTCOME_AUTHORIZED.equals(outcome)) {
                int attemptUpdated = checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                        attempt.getId(), CheckoutAttemptStatus.AUTHORIZED, CheckoutAttemptStatus.PENDING);
                if (attemptUpdated == 1) {
                    Booking booking = bookingRepository.findById(attempt.getBookingId())
                            .orElseThrow(() -> new BookingNotFoundException(attempt.getBookingId()));
                    bookingRepository.updateStatusIfCurrent(
                            booking.getId(), BookingStatus.CHECKED_OUT, BookingStatus.CHECKOUT_PENDING);
                    roomRepository.setStatus(booking.getRoomId(), RoomStatus.DIRTY);
                }
            } else if (OUTCOME_DECLINED.equals(outcome) || OUTCOME_FAILED.equals(outcome)) {
                CheckoutAttemptStatus newStatus = OUTCOME_DECLINED.equals(outcome)
                        ? CheckoutAttemptStatus.DECLINED
                        : CheckoutAttemptStatus.FAILED;
                int attemptUpdated = checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                        attempt.getId(), newStatus, CheckoutAttemptStatus.PENDING);
                if (attemptUpdated == 1) {
                    bookingRepository.updateStatusIfCurrent(
                            attempt.getBookingId(), BookingStatus.CHECKED_IN, BookingStatus.CHECKOUT_PENDING);
                }
            }
        } else if (attempt.getStatus() == CheckoutAttemptStatus.EXPIRED && OUTCOME_AUTHORIZED.equals(outcome)) {
            int attemptUpdated = checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                    attempt.getId(), CheckoutAttemptStatus.LATE_AUTHORIZED, CheckoutAttemptStatus.EXPIRED);
            if (attemptUpdated == 1) {
                log.warn("AUTHORIZED outcome arrived for checkout attempt {} (booking {}, amount={}) after it was "
                                + "already swept as EXPIRED — recording it as a payment (balance reduced by that "
                                + "amount); booking stays CHECKED_IN",
                        attempt.getId(), attempt.getBookingId(), attempt.getAmount());
            }
        }
        // Any other combination is a no-op (FR-012c).
    }

    /**
     * An outcome whose transactionId has no linked attempt yet. It is stored durably and applied when
     * the attempt links (or the sweep resolves it). A Kafka redelivery of the same transaction changes nothing.
     */
    @Transactional
    public void park(String transactionId, String eventId, String outcome) {
        unmatchedOutcomes.insertIfAbsent(transactionId, eventId, outcome, Instant.now());
        log.info("Parked {} outcome for transaction {} — no checkout attempt is linked to it yet",
                outcome, transactionId);
    }

    /**
     * Applies a parked outcome once its attempt is linked. The parked row is deleted only in the same
     * transaction as the applied outcome, so a failure leaves it parked. Returns false while unmatched.
     */
    @Transactional
    public boolean applyParked(String transactionId) {
        Optional<UnmatchedPaymentOutcome> parked = unmatchedOutcomes.findById(transactionId);
        if (parked.isEmpty()) {
            return false;
        }
        Optional<CheckoutPaymentAttempt> attempt = checkoutPaymentAttemptRepository.findByTransactionId(transactionId);
        if (attempt.isEmpty()) {
            return false;
        }
        applyOutcome(attempt.get(), parked.get().getOutcome());
        unmatchedOutcomes.deleteById(transactionId);
        return true;
    }

    /**
     * The sweep found a terminal outcome in transaction-service under the attempt's key. Links the
     * transactionId, applies the outcome, and applies any outcome parked for that transactionId.
     */
    @Transactional
    public void resolveStuckAttempt(UUID attemptId, String transactionId, String outcome) {
        linkTransaction(attemptId, transactionId);
        CheckoutPaymentAttempt attempt = checkoutPaymentAttemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalStateException("checkout attempt " + attemptId + " disappeared"));
        applyOutcome(attempt, outcome);
        applyParked(transactionId);
    }

    /** Parked outcomes older than the cutoff: still unmatched past the alert threshold. */
    @Transactional(readOnly = true)
    public List<UnmatchedPaymentOutcome> parkedBefore(Instant cutoff) {
        return unmatchedOutcomes.findByReceivedAtBefore(cutoff);
    }

    /** Every parked outcome, for the sweep to try to apply. */
    @Transactional(readOnly = true)
    public List<UnmatchedPaymentOutcome> allParked() {
        return unmatchedOutcomes.findAll();
    }
}
