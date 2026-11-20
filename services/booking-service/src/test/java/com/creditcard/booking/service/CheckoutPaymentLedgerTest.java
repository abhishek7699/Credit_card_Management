package com.creditcard.booking.service;

import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.CheckoutAttemptStatus;
import com.creditcard.booking.domain.CheckoutPaymentAttempt;
import com.creditcard.booking.domain.RoomStatus;
import com.creditcard.booking.domain.UnmatchedPaymentOutcome;
import com.creditcard.booking.exception.InvalidBookingStateException;
import com.creditcard.booking.kafka.event.TransactionStatusEvent;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.CheckoutPaymentAttemptRepository;
import com.creditcard.booking.repository.ProcessedEventRepository;
import com.creditcard.booking.repository.RoomRepository;
import com.creditcard.booking.repository.UnmatchedPaymentOutcomeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The checkout ledger: the attempt/booking/room writes, the outcome matrix, and parked outcomes. The
 * key case is an approval that arrives before its attempt is linked: it must be parked and then
 * applied when the link lands, never dropped.
 */
@ExtendWith(MockitoExtension.class)
class CheckoutPaymentLedgerTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private RoomRepository roomRepository;
    @Mock
    private CheckoutPaymentAttemptRepository attempts;
    @Mock
    private UnmatchedPaymentOutcomeRepository unmatched;
    @Mock
    private ProcessedEventRepository processedEvents;

    private CheckoutPaymentLedger ledger;
    private BookingService bookingService;

    private UUID bookingId;
    private UUID roomId;

    @BeforeEach
    void setUp() {
        ledger = new CheckoutPaymentLedger(bookingRepository, roomRepository, attempts, unmatched);
        bookingService = new BookingService(bookingRepository, roomRepository, null, processedEvents, null,
                attempts, null, null, ledger, "HOTEL-001");
        bookingId = UUID.randomUUID();
        roomId = UUID.randomUUID();
    }

    private static CheckoutPaymentAttempt attempt(UUID id, UUID bookingId, CheckoutAttemptStatus status) {
        CheckoutPaymentAttempt attempt = new CheckoutPaymentAttempt();
        ReflectionTestUtils.setField(attempt, "id", id);
        attempt.setBookingId(bookingId);
        attempt.setAmount(BigDecimal.TEN);
        attempt.setStatus(status);
        return attempt;
    }

    private Booking booking() {
        Booking booking = new Booking();
        ReflectionTestUtils.setField(booking, "id", bookingId);
        booking.setRoomId(roomId);
        return booking;
    }

    private static TransactionStatusEvent event(String transactionId, String outcome) {
        TransactionStatusEvent event = new TransactionStatusEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("transaction.status.updated");
        event.setOccurredAt(Instant.now());
        TransactionStatusEvent.Payload payload = new TransactionStatusEvent.Payload();
        payload.setTransactionId(transactionId);
        payload.setOutcome(outcome);
        event.setPayload(payload);
        return event;
    }

    // ── an approval that arrives before its attempt is linked ───────────────────

    @Test
    void approvalArrivingBeforeTheLink_isParked_thenAppliedWhenTheAttemptLinks_andTheParkedRowDeleted() {
        UUID attemptId = UUID.randomUUID();
        CheckoutPaymentAttempt pending = attempt(attemptId, bookingId, CheckoutAttemptStatus.PENDING);
        TransactionStatusEvent approval = event("txn-early", "AUTHORIZED");

        // 1. The outcome arrives first: no attempt is linked to txn-early yet.
        when(attempts.findByTransactionId("txn-early")).thenReturn(Optional.empty());
        bookingService.handlePaymentOutcome(approval);
        verify(unmatched).insertIfAbsent(eq("txn-early"), eq(approval.getEventId()), eq("AUTHORIZED"), any());
        verify(processedEvents, never()).insertIfAbsent(any());

        // 2. The attempt is linked to txn-early, then the parked approval is applied.
        UnmatchedPaymentOutcome parked = mock(UnmatchedPaymentOutcome.class);
        when(parked.getOutcome()).thenReturn("AUTHORIZED");
        when(unmatched.findById("txn-early")).thenReturn(Optional.of(parked));
        when(attempts.findByTransactionId("txn-early")).thenReturn(Optional.of(pending));
        when(attempts.updateStatusIfCurrent(attemptId, CheckoutAttemptStatus.AUTHORIZED, CheckoutAttemptStatus.PENDING))
                .thenReturn(1);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking()));
        when(bookingRepository.updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_OUT, BookingStatus.CHECKOUT_PENDING))
                .thenReturn(1);

        ledger.linkTransaction(attemptId, "txn-early");
        boolean applied = ledger.applyParked("txn-early");

        assertThat(applied).isTrue();
        verify(attempts).linkTransactionIfAbsent(attemptId, "txn-early");
        verify(bookingRepository).updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_OUT, BookingStatus.CHECKOUT_PENDING);
        verify(roomRepository).setStatus(roomId, RoomStatus.DIRTY);
        verify(unmatched).deleteById("txn-early");
    }

    @Test
    void applyParked_whileNoAttemptIsLinked_keepsTheRowAndReturnsFalse() {
        UnmatchedPaymentOutcome parked = mock(UnmatchedPaymentOutcome.class);
        when(unmatched.findById("txn-waiting")).thenReturn(Optional.of(parked));
        when(attempts.findByTransactionId("txn-waiting")).thenReturn(Optional.empty());

        assertThat(ledger.applyParked("txn-waiting")).isFalse();
        verify(unmatched, never()).deleteById(anyString());
    }

    @Test
    void applyParked_withNoParkedRow_doesNothing() {
        when(unmatched.findById("nothing")).thenReturn(Optional.empty());

        assertThat(ledger.applyParked("nothing")).isFalse();
        verify(attempts, never()).findByTransactionId(anyString());
    }

    @Test
    void redeliveredOutcome_isParkedOnce_becauseTheInsertIsIdempotent() {
        ledger.park("txn-dup", "evt-1", "DECLINED");
        ledger.park("txn-dup", "evt-1", "DECLINED");

        verify(unmatched, org.mockito.Mockito.times(2)).insertIfAbsent(eq("txn-dup"), eq("evt-1"), eq("DECLINED"), any());
    }

    // ── the outcome matrix ──────────────────────────────────────────────────────

    @Test
    void pendingDeclined_declinesTheAttemptAndReturnsTheBookingToCheckedIn() {
        UUID attemptId = UUID.randomUUID();
        CheckoutPaymentAttempt pending = attempt(attemptId, bookingId, CheckoutAttemptStatus.PENDING);
        when(attempts.updateStatusIfCurrent(attemptId, CheckoutAttemptStatus.DECLINED, CheckoutAttemptStatus.PENDING))
                .thenReturn(1);

        ledger.applyOutcome(pending, "DECLINED");

        verify(bookingRepository).updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_IN, BookingStatus.CHECKOUT_PENDING);
    }

    @Test
    void pendingFailed_failsTheAttemptAndReturnsTheBookingToCheckedIn() {
        UUID attemptId = UUID.randomUUID();
        CheckoutPaymentAttempt pending = attempt(attemptId, bookingId, CheckoutAttemptStatus.PENDING);
        when(attempts.updateStatusIfCurrent(attemptId, CheckoutAttemptStatus.FAILED, CheckoutAttemptStatus.PENDING))
                .thenReturn(1);

        ledger.applyOutcome(pending, "FAILED");

        verify(bookingRepository).updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_IN, BookingStatus.CHECKOUT_PENDING);
    }

    @Test
    void pendingAuthorized_alreadyChangedByAnotherWriter_doesNotTouchTheBooking() {
        UUID attemptId = UUID.randomUUID();
        CheckoutPaymentAttempt pending = attempt(attemptId, bookingId, CheckoutAttemptStatus.PENDING);
        when(attempts.updateStatusIfCurrent(attemptId, CheckoutAttemptStatus.AUTHORIZED, CheckoutAttemptStatus.PENDING))
                .thenReturn(0);

        ledger.applyOutcome(pending, "AUTHORIZED");

        verify(bookingRepository, never()).findById(any());
    }

    @Test
    void pendingDeclined_alreadyChangedByAnotherWriter_doesNotTouchTheBooking() {
        UUID attemptId = UUID.randomUUID();
        CheckoutPaymentAttempt pending = attempt(attemptId, bookingId, CheckoutAttemptStatus.PENDING);
        when(attempts.updateStatusIfCurrent(attemptId, CheckoutAttemptStatus.DECLINED, CheckoutAttemptStatus.PENDING))
                .thenReturn(0);

        ledger.applyOutcome(pending, "DECLINED");

        verify(bookingRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void expiredAuthorized_isRecordedAsLateAuthorized_andTheBookingStaysCheckedIn() {
        UUID attemptId = UUID.randomUUID();
        CheckoutPaymentAttempt expired = attempt(attemptId, bookingId, CheckoutAttemptStatus.EXPIRED);
        when(attempts.updateStatusIfCurrent(attemptId, CheckoutAttemptStatus.LATE_AUTHORIZED, CheckoutAttemptStatus.EXPIRED))
                .thenReturn(1);

        ledger.applyOutcome(expired, "AUTHORIZED");

        verify(attempts).updateStatusIfCurrent(attemptId, CheckoutAttemptStatus.LATE_AUTHORIZED, CheckoutAttemptStatus.EXPIRED);
        verify(bookingRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void expiredAuthorized_alreadyRecorded_isNoOp() {
        UUID attemptId = UUID.randomUUID();
        CheckoutPaymentAttempt expired = attempt(attemptId, bookingId, CheckoutAttemptStatus.EXPIRED);
        when(attempts.updateStatusIfCurrent(attemptId, CheckoutAttemptStatus.LATE_AUTHORIZED, CheckoutAttemptStatus.EXPIRED))
                .thenReturn(0);

        ledger.applyOutcome(expired, "AUTHORIZED");

        verify(bookingRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void expiredDeclined_isNoOp_nothingToRecord() {
        CheckoutPaymentAttempt expired = attempt(UUID.randomUUID(), bookingId, CheckoutAttemptStatus.EXPIRED);

        ledger.applyOutcome(expired, "DECLINED");

        verify(attempts, never()).updateStatusIfCurrent(any(), any(), any());
        verify(bookingRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void unrecognisedOutcomeOnAPendingAttempt_isNoOp() {
        CheckoutPaymentAttempt pending = attempt(UUID.randomUUID(), bookingId, CheckoutAttemptStatus.PENDING);

        ledger.applyOutcome(pending, "SOMETHING_NEW");

        verify(attempts, never()).updateStatusIfCurrent(any(), any(), any());
    }

    // ── begin and the zero-balance path ─────────────────────────────────────────

    @Test
    void begin_savesAPendingAttemptAndMovesTheBookingToCheckoutPending() {
        when(bookingRepository.updateStatusIfCurrent(bookingId, BookingStatus.CHECKOUT_PENDING, BookingStatus.CHECKED_IN))
                .thenReturn(1);
        when(attempts.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CheckoutPaymentAttempt saved = ledger.begin(bookingId, new BigDecimal("24.50"));

        assertThat(saved.getStatus()).isEqualTo(CheckoutAttemptStatus.PENDING);
        assertThat(saved.getAmount()).isEqualByComparingTo("24.50");
        assertThat(saved.getBookingId()).isEqualTo(bookingId);
    }

    @Test
    void begin_whenTheBookingIsNoLongerCheckedIn_refusesAndSavesNothing() {
        when(bookingRepository.updateStatusIfCurrent(bookingId, BookingStatus.CHECKOUT_PENDING, BookingStatus.CHECKED_IN))
                .thenReturn(0);

        assertThatThrownBy(() -> ledger.begin(bookingId, BigDecimal.TEN))
                .isInstanceOf(InvalidBookingStateException.class);
        verify(attempts, never()).save(any());
    }

    @Test
    void completeWithNoBalance_checksOutTheBookingAndMarksTheRoomDirty() {
        Booking booking = booking();
        when(bookingRepository.updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_OUT, BookingStatus.CHECKED_IN))
                .thenReturn(1);

        ledger.completeWithNoBalance(booking);

        verify(roomRepository).setStatus(roomId, RoomStatus.DIRTY);
    }

    @Test
    void completeWithNoBalance_whenTheBookingMovedOn_refuses() {
        Booking booking = booking();
        when(bookingRepository.updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_OUT, BookingStatus.CHECKED_IN))
                .thenReturn(0);

        assertThatThrownBy(() -> ledger.completeWithNoBalance(booking))
                .isInstanceOf(InvalidBookingStateException.class);
        verify(roomRepository, never()).setStatus(any(), any());
    }

    // ── the sweep's resolution path ─────────────────────────────────────────────

    @Test
    void resolveStuckAttempt_linksAppliesTheOutcome_andAppliesAnyParkedOutcomeForThatTransaction() {
        UUID attemptId = UUID.randomUUID();
        CheckoutPaymentAttempt pending = attempt(attemptId, bookingId, CheckoutAttemptStatus.PENDING);
        when(attempts.findById(attemptId)).thenReturn(Optional.of(pending));
        when(attempts.updateStatusIfCurrent(attemptId, CheckoutAttemptStatus.DECLINED, CheckoutAttemptStatus.PENDING))
                .thenReturn(1);
        when(unmatched.findById("txn-resolved")).thenReturn(Optional.empty());

        ledger.resolveStuckAttempt(attemptId, "txn-resolved", "DECLINED");

        verify(attempts).linkTransactionIfAbsent(attemptId, "txn-resolved");
        verify(bookingRepository).updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_IN, BookingStatus.CHECKOUT_PENDING);
    }
}
