package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.BookingRequest;
import com.creditcard.booking.controller.dto.ChargeRequest;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.CancellationReason;
import com.creditcard.booking.domain.Charge;
import com.creditcard.booking.domain.ChargeType;
import com.creditcard.booking.domain.CheckoutAttemptStatus;
import com.creditcard.booking.domain.CheckoutPaymentAttempt;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.domain.RoomStatus;
import com.creditcard.booking.exception.BookingNotFoundException;
import com.creditcard.booking.exception.CardRequiredException;
import com.creditcard.booking.exception.InvalidBookingStateException;
import com.creditcard.booking.exception.InvalidDateRangeException;
import com.creditcard.booking.exception.RoomNotFoundException;
import com.creditcard.booking.exception.RoomUnavailableException;
import com.creditcard.booking.kafka.BookingNotFoundForTransactionException;
import com.creditcard.booking.kafka.event.TransactionStatusEvent;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.BookingRoomNightRepository;
import com.creditcard.booking.repository.ChargeRepository;
import com.creditcard.booking.repository.CheckoutPaymentAttemptRepository;
import com.creditcard.booking.repository.ProcessedEventRepository;
import com.creditcard.booking.repository.RoomRepository;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private RoomRepository roomRepository;
    @Mock
    private BookingRoomNightRepository bookingRoomNightRepository;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private ChargeRepository chargeRepository;
    @Mock
    private CheckoutPaymentAttemptRepository checkoutPaymentAttemptRepository;
    @Mock
    private TransactionServiceGrpc.TransactionServiceBlockingStub transactionServiceBlockingStub;

    private BookingService service;

    @BeforeEach
    void setUp() {
        service = new BookingService(bookingRepository, roomRepository, bookingRoomNightRepository,
                processedEventRepository, chargeRepository, checkoutPaymentAttemptRepository,
                transactionServiceBlockingStub, "HOTEL-001");
    }

    private static Room room(UUID id) {
        Room room = new Room();
        ReflectionTestUtils.setField(room, "id", id);
        room.setRoomNumber("101");
        room.setRoomType("STANDARD");
        room.setNightlyRate(new BigDecimal("100.00"));
        return room;
    }

    private static Booking booking(UUID id, BookingStatus status, String transactionId) {
        Booking booking = new Booking();
        ReflectionTestUtils.setField(booking, "id", id);
        booking.setRoomId(UUID.randomUUID());
        booking.setGuestName("Jane Doe");
        booking.setCheckIn(LocalDate.of(2026, 11, 1));
        booking.setCheckOut(LocalDate.of(2026, 11, 3));
        booking.setStatus(status);
        booking.setTransactionId(transactionId);
        // Set by default so getBalanceDue (used inside checkOut) never needs a Room
        // lookup in tests unrelated to the legacy-null-roomChargeAmount fallback —
        // see getBalanceDue_legacyBookingWithNullRoomChargeAmount_fallsBackToRoomLookup
        // for the one test that deliberately leaves this null.
        booking.setRoomChargeAmount(new BigDecimal("200.00"));
        return booking;
    }

    // ── createHeldBooking ───────────────────────────────────────────────────────

    @Test
    void createHeldBooking_checkOutNotAfterCheckIn_throwsInvalidDateRange() {
        LocalDate date = LocalDate.of(2026, 11, 1);
        BookingRequest request = new BookingRequest(UUID.randomUUID(), date, date, "Jane",
                new BookingRequest.CardDetails("4111111111111111", "12/28", "123", "Jane"));

        assertThatThrownBy(() -> service.createHeldBooking(request))
                .isInstanceOf(InvalidDateRangeException.class);
    }

    @Test
    void createHeldBooking_roomNotFound_throwsRoomNotFound() {
        UUID roomId = UUID.randomUUID();
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());
        BookingRequest request = new BookingRequest(roomId, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3),
                "Jane", new BookingRequest.CardDetails("4111111111111111", "12/28", "123", "Jane"));

        assertThatThrownBy(() -> service.createHeldBooking(request))
                .isInstanceOf(RoomNotFoundException.class);
    }

    @Test
    void createHeldBooking_available_savesHeldBookingAndNights() {
        UUID roomId = UUID.randomUUID();
        Room room = room(roomId);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(bookingRepository.save(any())).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
            return b;
        });
        BookingRequest request = new BookingRequest(roomId, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3),
                "Jane", new BookingRequest.CardDetails("4111111111111111", "12/28", "123", "Jane"));

        Booking result = service.createHeldBooking(request);

        assertThat(result.getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(result.getRoomId()).isEqualTo(roomId);
        verify(bookingRoomNightRepository).saveAll(any());
        verify(bookingRoomNightRepository).flush();
    }

    @Test
    void createHeldBooking_nightsConflict_throwsRoomUnavailable() {
        UUID roomId = UUID.randomUUID();
        Room room = room(roomId);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new DataIntegrityViolationException("duplicate key")).when(bookingRoomNightRepository).flush();
        BookingRequest request = new BookingRequest(roomId, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3),
                "Jane", new BookingRequest.CardDetails("4111111111111111", "12/28", "123", "Jane"));

        assertThatThrownBy(() -> service.createHeldBooking(request))
                .isInstanceOf(RoomUnavailableException.class);
    }

    // ── cancel ──────────────────────────────────────────────────────────────────

    @Test
    void cancel_setsStatusAndReason_andDeletesRoomNightsInSameCall() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.HELD, "txn-cancel");

        service.cancel(booking, CancellationReason.HOLD_EXPIRED);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getCancellationReason()).isEqualTo(CancellationReason.HOLD_EXPIRED);
        verify(bookingRepository).save(booking);
        verify(bookingRoomNightRepository).deleteByBookingId(booking.getId());
    }

    // ── getById ─────────────────────────────────────────────────────────────────

    @Test
    void getById_found_returnsBooking() {
        UUID id = UUID.randomUUID();
        Booking booking = booking(id, BookingStatus.HELD, null);
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));

        assertThat(service.getById(id)).isSameAs(booking);
    }

    @Test
    void getById_notFound_throwsBookingNotFound() {
        UUID id = UUID.randomUUID();
        when(bookingRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(BookingNotFoundException.class);
    }

    // ── handlePaymentOutcome ────────────────────────────────────────────────────

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

    @Test
    void handlePaymentOutcome_noMatchingBooking_throwsForRetry_andNeverWritesProcessedEvent() {
        when(bookingRepository.findByTransactionId("txn-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handlePaymentOutcome(event("txn-missing", "AUTHORIZED")))
                .isInstanceOf(BookingNotFoundForTransactionException.class);

        verify(processedEventRepository, never()).insertIfAbsent(any());
    }

    @Test
    void handlePaymentOutcome_heldAndAuthorized_confirms() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.HELD, "txn-1");
        when(bookingRepository.findByTransactionId("txn-1")).thenReturn(Optional.of(booking));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);

        service.handlePaymentOutcome(event("txn-1", "AUTHORIZED"));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(bookingRepository).save(booking);
    }

    @Test
    void handlePaymentOutcome_heldAndDeclined_cancelsWithPaymentDeclinedReason() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.HELD, "txn-2");
        when(bookingRepository.findByTransactionId("txn-2")).thenReturn(Optional.of(booking));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);

        service.handlePaymentOutcome(event("txn-2", "DECLINED"));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getCancellationReason()).isEqualTo(CancellationReason.PAYMENT_DECLINED);
        verify(bookingRoomNightRepository).deleteByBookingId(booking.getId());
    }

    @Test
    void handlePaymentOutcome_heldAndFailed_cancelsWithPaymentFailedReason() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.HELD, "txn-2b");
        when(bookingRepository.findByTransactionId("txn-2b")).thenReturn(Optional.of(booking));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);

        service.handlePaymentOutcome(event("txn-2b", "FAILED"));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getCancellationReason()).isEqualTo(CancellationReason.PAYMENT_FAILED);
        verify(bookingRoomNightRepository).deleteByBookingId(booking.getId());
    }

    @Test
    void handlePaymentOutcome_heldWithUnrecognizedOutcome_isNoOp() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.HELD, "txn-2c");
        when(bookingRepository.findByTransactionId("txn-2c")).thenReturn(Optional.of(booking));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);

        service.handlePaymentOutcome(event("txn-2c", "PENDING"));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.HELD);
        verify(bookingRepository, never()).save(booking);
        verify(bookingRoomNightRepository, never()).deleteByBookingId(any());
    }

    @Test
    void handlePaymentOutcome_alreadyConfirmed_isNoOpRegardlessOfOutcome() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.CONFIRMED, "txn-4");
        when(bookingRepository.findByTransactionId("txn-4")).thenReturn(Optional.of(booking));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);

        service.handlePaymentOutcome(event("txn-4", "AUTHORIZED"));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(bookingRepository, never()).save(booking);
        verify(bookingRoomNightRepository, never()).deleteByBookingId(any());
    }

    @Test
    void handlePaymentOutcome_lateAuthorizedAfterHoldExpiry_staysCancelled_reasonBecomesPaymentAfterExpiry() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.CANCELLED, "txn-5");
        booking.setCancellationReason(CancellationReason.HOLD_EXPIRED);
        when(bookingRepository.findByTransactionId("txn-5")).thenReturn(Optional.of(booking));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);

        service.handlePaymentOutcome(event("txn-5", "AUTHORIZED"));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getCancellationReason()).isEqualTo(CancellationReason.PAYMENT_AFTER_EXPIRY);
        verify(bookingRepository).save(booking);
        verify(bookingRoomNightRepository, never()).deleteByBookingId(any());
    }

    @Test
    void handlePaymentOutcome_cancelledForOtherReason_authorizedOutcome_isNoOp() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.CANCELLED, "txn-6");
        booking.setCancellationReason(CancellationReason.PAYMENT_DECLINED);
        when(bookingRepository.findByTransactionId("txn-6")).thenReturn(Optional.of(booking));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);

        service.handlePaymentOutcome(event("txn-6", "AUTHORIZED"));

        assertThat(booking.getCancellationReason()).isEqualTo(CancellationReason.PAYMENT_DECLINED);
        verify(bookingRepository, never()).save(booking);
    }

    @Test
    void handlePaymentOutcome_holdExpired_declinedOutcome_isNoOp() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.CANCELLED, "txn-7");
        booking.setCancellationReason(CancellationReason.HOLD_EXPIRED);
        when(bookingRepository.findByTransactionId("txn-7")).thenReturn(Optional.of(booking));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);

        service.handlePaymentOutcome(event("txn-7", "DECLINED"));

        assertThat(booking.getCancellationReason()).isEqualTo(CancellationReason.HOLD_EXPIRED);
        verify(bookingRepository, never()).save(booking);
    }

    // ── handlePaymentOutcome: checkout saga fallback (layer 2c) ─────────────────

    private static CheckoutPaymentAttempt checkoutAttempt(UUID bookingId, String transactionId,
                                                            CheckoutAttemptStatus status, String amount) {
        CheckoutPaymentAttempt attempt = new CheckoutPaymentAttempt();
        ReflectionTestUtils.setField(attempt, "id", UUID.randomUUID());
        attempt.setBookingId(bookingId);
        attempt.setTransactionId(transactionId);
        attempt.setAmount(new BigDecimal(amount));
        attempt.setStatus(status);
        return attempt;
    }

    @Test
    void handlePaymentOutcome_transactionIdMatchesNoBooking_fallsBackToCheckoutAttempt_authorized_checksOutAndSetsRoomDirty() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = booking(bookingId, BookingStatus.CHECKOUT_PENDING, null);
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-1", CheckoutAttemptStatus.PENDING, "24.50");
        when(bookingRepository.findByTransactionId("txn-co-1")).thenReturn(Optional.empty());
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-1")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);
        when(checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                attempt.getId(), CheckoutAttemptStatus.AUTHORIZED, CheckoutAttemptStatus.PENDING)).thenReturn(1);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));

        service.handlePaymentOutcome(event("txn-co-1", "AUTHORIZED"));

        verify(bookingRepository).updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_OUT, BookingStatus.CHECKOUT_PENDING);
        verify(roomRepository).setStatus(booking.getRoomId(), RoomStatus.DIRTY);
    }

    @Test
    void handlePaymentOutcome_checkoutAttemptPendingDeclined_revertsBookingToCheckedIn() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-2", CheckoutAttemptStatus.PENDING, "24.50");
        when(bookingRepository.findByTransactionId("txn-co-2")).thenReturn(Optional.empty());
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-2")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);
        when(checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                attempt.getId(), CheckoutAttemptStatus.DECLINED, CheckoutAttemptStatus.PENDING)).thenReturn(1);

        service.handlePaymentOutcome(event("txn-co-2", "DECLINED"));

        verify(bookingRepository).updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_IN, BookingStatus.CHECKOUT_PENDING);
        verify(roomRepository, never()).setStatus(any(), any());
    }

    @Test
    void handlePaymentOutcome_checkoutAttemptPendingFailed_revertsBookingToCheckedIn() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-2f", CheckoutAttemptStatus.PENDING, "24.50");
        when(bookingRepository.findByTransactionId("txn-co-2f")).thenReturn(Optional.empty());
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-2f")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);
        when(checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                attempt.getId(), CheckoutAttemptStatus.FAILED, CheckoutAttemptStatus.PENDING)).thenReturn(1);

        service.handlePaymentOutcome(event("txn-co-2f", "FAILED"));

        verify(bookingRepository).updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_IN, BookingStatus.CHECKOUT_PENDING);
    }

    @Test
    void handlePaymentOutcome_checkoutAttemptAlreadyResolved_raceLost_isNoOp() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-3", CheckoutAttemptStatus.PENDING, "24.50");
        when(bookingRepository.findByTransactionId("txn-co-3")).thenReturn(Optional.empty());
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-3")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);
        // Lost the race (e.g. the hold-expiry sweep already moved it to EXPIRED
        // between the read above and this update) -- 0 rows affected.
        when(checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                attempt.getId(), CheckoutAttemptStatus.AUTHORIZED, CheckoutAttemptStatus.PENDING)).thenReturn(0);

        service.handlePaymentOutcome(event("txn-co-3", "AUTHORIZED"));

        verify(bookingRepository, never()).updateStatusIfCurrent(any(), eq(BookingStatus.CHECKED_OUT), any());
        verify(roomRepository, never()).setStatus(any(), any());
    }

    @Test
    void handlePaymentOutcome_lateAuthorizedAfterSweepExpiry_recordsAsPayment_bookingStaysCheckedIn() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-4", CheckoutAttemptStatus.EXPIRED, "24.50");
        when(bookingRepository.findByTransactionId("txn-co-4")).thenReturn(Optional.empty());
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-4")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);
        when(checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                attempt.getId(), CheckoutAttemptStatus.LATE_AUTHORIZED, CheckoutAttemptStatus.EXPIRED)).thenReturn(1);

        service.handlePaymentOutcome(event("txn-co-4", "AUTHORIZED"));

        verify(checkoutPaymentAttemptRepository).updateStatusIfCurrent(
                attempt.getId(), CheckoutAttemptStatus.LATE_AUTHORIZED, CheckoutAttemptStatus.EXPIRED);
        // Never a reverse transition on the booking itself -- FR-012b records the
        // payment on the attempt only; the booking already reverted to CHECKED_IN
        // when the sweep expired this attempt.
        verify(bookingRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void handlePaymentOutcome_expiredAttempt_lateDeclined_isTrueNoOp() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-5", CheckoutAttemptStatus.EXPIRED, "24.50");
        when(bookingRepository.findByTransactionId("txn-co-5")).thenReturn(Optional.empty());
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-5")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);

        service.handlePaymentOutcome(event("txn-co-5", "DECLINED"));

        verify(checkoutPaymentAttemptRepository, never()).updateStatusIfCurrent(any(), any(), any());
        verify(bookingRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void handlePaymentOutcome_expiredAttempt_lateFailed_isTrueNoOp() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-5f", CheckoutAttemptStatus.EXPIRED, "24.50");
        when(bookingRepository.findByTransactionId("txn-co-5f")).thenReturn(Optional.empty());
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-5f")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);

        service.handlePaymentOutcome(event("txn-co-5f", "FAILED"));

        verify(checkoutPaymentAttemptRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void handlePaymentOutcome_noMatchOnEitherBookingOrAttempt_throwsForRetry() {
        when(bookingRepository.findByTransactionId("txn-co-missing")).thenReturn(Optional.empty());
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handlePaymentOutcome(event("txn-co-missing", "AUTHORIZED")))
                .isInstanceOf(BookingNotFoundForTransactionException.class);

        verify(processedEventRepository, never()).insertIfAbsent(any());
    }

    @Test
    void handlePaymentOutcome_checkoutAttemptDuplicateEvent_isNoOp() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-dup", CheckoutAttemptStatus.PENDING, "24.50");
        when(bookingRepository.findByTransactionId("txn-co-dup")).thenReturn(Optional.empty());
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-dup")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(0);

        service.handlePaymentOutcome(event("txn-co-dup", "AUTHORIZED"));

        verify(checkoutPaymentAttemptRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void handleCheckoutOutcome_attemptAlreadyResolved_neitherPendingNorExpired_isNoOp() {
        UUID bookingId = UUID.randomUUID();
        // Already AUTHORIZED (e.g. a different, later Kafka message for the same
        // transactionId) -- status is neither PENDING nor EXPIRED, so neither
        // branch applies.
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-9", CheckoutAttemptStatus.AUTHORIZED, "24.50");
        when(bookingRepository.findByTransactionId("txn-co-9")).thenReturn(Optional.empty());
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-9")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);

        service.handlePaymentOutcome(event("txn-co-9", "AUTHORIZED"));

        verify(checkoutPaymentAttemptRepository, never()).updateStatusIfCurrent(any(), any(), any());
        verify(bookingRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void handleCheckoutOutcome_pendingAttempt_unrecognizedOutcome_isTrueNoOp() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-6", CheckoutAttemptStatus.PENDING, "24.50");
        when(bookingRepository.findByTransactionId("txn-co-6")).thenReturn(Optional.empty());
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-6")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);

        service.handlePaymentOutcome(event("txn-co-6", "PENDING"));

        verify(checkoutPaymentAttemptRepository, never()).updateStatusIfCurrent(any(), any(), any());
        verify(bookingRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void handleCheckoutOutcome_pendingAttempt_declinedRaceLost_isNoOp() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-7", CheckoutAttemptStatus.PENDING, "24.50");
        when(bookingRepository.findByTransactionId("txn-co-7")).thenReturn(Optional.empty());
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-7")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);
        // Lost the race: the hold-expiry sweep already moved this attempt to EXPIRED
        // between the read above and this update.
        when(checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                attempt.getId(), CheckoutAttemptStatus.DECLINED, CheckoutAttemptStatus.PENDING)).thenReturn(0);

        service.handlePaymentOutcome(event("txn-co-7", "DECLINED"));

        verify(bookingRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void handleCheckoutOutcome_expiredAttempt_lateAuthorizedRaceLost_isNoOp() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-8", CheckoutAttemptStatus.EXPIRED, "24.50");
        when(bookingRepository.findByTransactionId("txn-co-8")).thenReturn(Optional.empty());
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-8")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);
        // Lost the race: a duplicate late-AUTHORIZED delivery (different eventId,
        // same transactionId) already resolved this attempt first.
        when(checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                attempt.getId(), CheckoutAttemptStatus.LATE_AUTHORIZED, CheckoutAttemptStatus.EXPIRED)).thenReturn(0);

        service.handlePaymentOutcome(event("txn-co-8", "AUTHORIZED"));

        verify(bookingRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    // ── addCharge / getCharges / getBalanceDue (layer 2c, US1) ──────────────────

    @Test
    void addCharge_checkedInBooking_succeeds_savesCharge() {
        UUID bookingId = UUID.randomUUID();
        Booking checkedIn = booking(bookingId, BookingStatus.CHECKED_IN, "txn-charge-1");
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(checkedIn));
        when(chargeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ChargeRequest request = new ChargeRequest(ChargeType.ROOM_SERVICE, "Club sandwich", new BigDecimal("18.50"));

        Charge result = service.addCharge(bookingId, request);

        assertThat(result.getBookingId()).isEqualTo(bookingId);
        assertThat(result.getType()).isEqualTo(ChargeType.ROOM_SERVICE);
        assertThat(result.getDescription()).isEqualTo("Club sandwich");
        assertThat(result.getAmount()).isEqualByComparingTo(new BigDecimal("18.50"));
    }

    @Test
    void addCharge_bookingNotCheckedIn_rejected_savesNothing() {
        UUID bookingId = UUID.randomUUID();
        Booking confirmed = booking(bookingId, BookingStatus.CONFIRMED, "txn-charge-2");
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(confirmed));
        ChargeRequest request = new ChargeRequest(ChargeType.MINIBAR, "Soda", new BigDecimal("6.00"));

        assertThatThrownBy(() -> service.addCharge(bookingId, request))
                .isInstanceOf(InvalidBookingStateException.class);

        verify(chargeRepository, never()).save(any());
    }

    @Test
    void getCharges_delegatesToRepository_orderedByCreatedAt() {
        UUID bookingId = UUID.randomUUID();
        Charge charge = new Charge();
        charge.setBookingId(bookingId);
        when(chargeRepository.findByBookingIdOrderByCreatedAtAsc(bookingId)).thenReturn(List.of(charge));

        assertThat(service.getCharges(bookingId)).containsExactly(charge);
    }

    @Test
    void getBalanceDue_sumsChargesAndSubtractsPaidCheckoutAttempts() {
        UUID bookingId = UUID.randomUUID();
        Booking checkedIn = booking(bookingId, BookingStatus.CHECKED_IN, "txn-balance-1");
        Charge roomService = new Charge();
        roomService.setAmount(new BigDecimal("18.50"));
        Charge minibar = new Charge();
        minibar.setAmount(new BigDecimal("6.00"));
        CheckoutPaymentAttempt paidAttempt = checkoutAttempt(bookingId, "txn-prior", CheckoutAttemptStatus.AUTHORIZED, "10.00");
        when(chargeRepository.findByBookingIdOrderByCreatedAtAsc(bookingId)).thenReturn(List.of(roomService, minibar));
        when(checkoutPaymentAttemptRepository.findByBookingIdInAndStatusIn(List.of(bookingId),
                List.of(CheckoutAttemptStatus.AUTHORIZED, CheckoutAttemptStatus.LATE_AUTHORIZED)))
                .thenReturn(List.of(paidAttempt));

        BigDecimal balance = service.getBalanceDue(checkedIn);

        // roomChargeAmount's two terms cancel regardless of its own value -- only
        // charges (24.50) minus what's already been paid at checkout (10.00) survive.
        assertThat(balance).isEqualByComparingTo(new BigDecimal("14.50"));
    }

    @Test
    void getBalanceDue_legacyBookingWithNullRoomChargeAmount_fallsBackToNightsTimesRate() {
        UUID bookingId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Booking legacyBooking = booking(bookingId, BookingStatus.CHECKED_IN, "txn-legacy-1");
        legacyBooking.setRoomId(roomId);
        legacyBooking.setRoomChargeAmount(null); // pre-V6 data -- never had this column
        Room room = room(roomId); // nightlyRate 100.00; booking() sets 2 nights (Nov 1 -> Nov 3)
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        Charge minibar = new Charge();
        minibar.setAmount(new BigDecimal("6.00"));
        when(chargeRepository.findByBookingIdOrderByCreatedAtAsc(bookingId)).thenReturn(List.of(minibar));

        BigDecimal balance = service.getBalanceDue(legacyBooking);

        // The fallback (200.00, recomputed from the room's current rate) cancels out
        // of the formula exactly the same as a persisted roomChargeAmount would --
        // only the 6.00 charge (nothing paid yet) survives.
        assertThat(balance).isEqualByComparingTo(new BigDecimal("6.00"));
        verify(roomRepository).findById(roomId);
    }

    @Test
    void getBalanceDue_legacyBookingWithNullRoomChargeAmount_roomDeleted_throwsRoomNotFound() {
        UUID bookingId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Booking legacyBooking = booking(bookingId, BookingStatus.CHECKED_IN, "txn-legacy-2");
        legacyBooking.setRoomId(roomId);
        legacyBooking.setRoomChargeAmount(null);
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBalanceDue(legacyBooking)).isInstanceOf(RoomNotFoundException.class);
    }

    @Test
    void initiatePayment_roomNoLongerExists_throwsRoomNotFound() {
        UUID roomId = UUID.randomUUID();
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());
        Booking booking = booking(UUID.randomUUID(), BookingStatus.HELD, null);
        booking.setRoomId(roomId);

        assertThatThrownBy(() -> service.initiatePayment(booking,
                new BookingRequest.CardDetails("4111111111111111", "12/28", "123", "Jane")))
                .isInstanceOf(RoomNotFoundException.class);
    }

    // ── checkIn (layer 2b) ──────────────────────────────────────────────────────

    @Test
    void checkIn_confirmedAndVacantClean_succeeds() {
        UUID bookingId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Booking confirmed = booking(bookingId, BookingStatus.CONFIRMED, "txn-ci-1");
        confirmed.setRoomId(roomId);
        Booking checkedIn = booking(bookingId, BookingStatus.CHECKED_IN, "txn-ci-1");
        checkedIn.setRoomId(roomId);
        when(bookingRepository.findById(bookingId))
                .thenReturn(Optional.of(confirmed))
                .thenReturn(Optional.of(checkedIn));
        when(roomRepository.updateStatusIfCurrent(roomId, RoomStatus.OCCUPIED, RoomStatus.VACANT_CLEAN))
                .thenReturn(1);
        when(bookingRepository.updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_IN, BookingStatus.CONFIRMED))
                .thenReturn(1);

        Booking result = service.checkIn(bookingId);

        assertThat(result.getStatus()).isEqualTo(BookingStatus.CHECKED_IN);
    }

    @Test
    void checkIn_bookingNotConfirmed_throwsInvalidBookingState_andNeverTouchesRoom() {
        UUID bookingId = UUID.randomUUID();
        Booking held = booking(bookingId, BookingStatus.HELD, null);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(held));

        assertThatThrownBy(() -> service.checkIn(bookingId)).isInstanceOf(InvalidBookingStateException.class);

        verify(roomRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void checkIn_roomNotVacantClean_throwsInvalidBookingState_andNeverTouchesBooking() {
        UUID bookingId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Booking confirmed = booking(bookingId, BookingStatus.CONFIRMED, "txn-ci-2");
        confirmed.setRoomId(roomId);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(confirmed));
        when(roomRepository.updateStatusIfCurrent(roomId, RoomStatus.OCCUPIED, RoomStatus.VACANT_CLEAN))
                .thenReturn(0);

        assertThatThrownBy(() -> service.checkIn(bookingId)).isInstanceOf(InvalidBookingStateException.class);

        verify(bookingRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void checkIn_lostRaceOnBookingUpdate_throwsInvalidBookingState() {
        UUID bookingId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Booking confirmed = booking(bookingId, BookingStatus.CONFIRMED, "txn-ci-3");
        confirmed.setRoomId(roomId);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(confirmed));
        when(roomRepository.updateStatusIfCurrent(roomId, RoomStatus.OCCUPIED, RoomStatus.VACANT_CLEAN))
                .thenReturn(1);
        when(bookingRepository.updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_IN, BookingStatus.CONFIRMED))
                .thenReturn(0);

        assertThatThrownBy(() -> service.checkIn(bookingId)).isInstanceOf(InvalidBookingStateException.class);
    }

    // ── checkOut (layer 2b) ─────────────────────────────────────────────────────

    @Test
    void checkOut_checkedIn_succeeds_andSetsRoomDirtyUnconditionally() {
        UUID bookingId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Booking checkedIn = booking(bookingId, BookingStatus.CHECKED_IN, "txn-co-1");
        checkedIn.setRoomId(roomId);
        Booking checkedOut = booking(bookingId, BookingStatus.CHECKED_OUT, "txn-co-1");
        checkedOut.setRoomId(roomId);
        when(bookingRepository.findById(bookingId))
                .thenReturn(Optional.of(checkedIn))
                .thenReturn(Optional.of(checkedOut));
        when(bookingRepository.updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_OUT, BookingStatus.CHECKED_IN))
                .thenReturn(1);

        Booking result = service.checkOut(bookingId, null);

        assertThat(result.getStatus()).isEqualTo(BookingStatus.CHECKED_OUT);
        verify(roomRepository).setStatus(roomId, RoomStatus.DIRTY);
    }

    @Test
    void checkOut_bookingNotCheckedIn_throwsInvalidBookingState_andNeverTouchesRoom() {
        UUID bookingId = UUID.randomUUID();
        Booking confirmed = booking(bookingId, BookingStatus.CONFIRMED, "txn-co-2");
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(confirmed));

        assertThatThrownBy(() -> service.checkOut(bookingId, null)).isInstanceOf(InvalidBookingStateException.class);

        verify(roomRepository, never()).setStatus(any(), any());
    }

    @Test
    void checkOut_lostRaceOnBookingUpdate_throwsInvalidBookingState_andNeverTouchesRoom() {
        UUID bookingId = UUID.randomUUID();
        Booking checkedIn = booking(bookingId, BookingStatus.CHECKED_IN, "txn-co-3");
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(checkedIn));
        when(bookingRepository.updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_OUT, BookingStatus.CHECKED_IN))
                .thenReturn(0);

        assertThatThrownBy(() -> service.checkOut(bookingId, null)).isInstanceOf(InvalidBookingStateException.class);

        verify(roomRepository, never()).setStatus(any(), any());
    }

    // ── checkOut with a balance due (layer 2c, US3/US4) ─────────────────────────
    // The success path (gRPC actually called) lives in BookingServiceCheckOutTest,
    // which uses a real in-process gRPC server -- withDeadlineAfter is final on
    // grpc-java's AbstractStub, so a bare Mockito mock NPEs on it (same reason
    // BookingServiceInitiatePaymentTest exists for initiatePayment). Only the
    // branches that return *before* reaching the gRPC call belong here.

    @Test
    void checkOut_balanceDue_noCard_throws400_beforeAnyAttemptCreated() {
        UUID bookingId = UUID.randomUUID();
        Booking checkedIn = booking(bookingId, BookingStatus.CHECKED_IN, "txn-bal-1");
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(checkedIn));
        Charge charge = new Charge();
        charge.setAmount(new BigDecimal("24.50"));
        when(chargeRepository.findByBookingIdOrderByCreatedAtAsc(bookingId)).thenReturn(List.of(charge));

        assertThatThrownBy(() -> service.checkOut(bookingId, null)).isInstanceOf(CardRequiredException.class);

        verify(checkoutPaymentAttemptRepository, never()).save(any());
        verify(bookingRepository, never()).updateStatusIfCurrent(any(), eq(BookingStatus.CHECKOUT_PENDING), any());
    }

    @Test
    void checkOut_balanceDue_secondAttemptAlreadyInFlight_rejectedWith409_noNewAttemptCreated() {
        UUID bookingId = UUID.randomUUID();
        Booking checkedIn = booking(bookingId, BookingStatus.CHECKED_IN, "txn-bal-2");
        Charge charge = new Charge();
        charge.setAmount(new BigDecimal("24.50"));
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(checkedIn));
        when(chargeRepository.findByBookingIdOrderByCreatedAtAsc(bookingId)).thenReturn(List.of(charge));
        // Lost the race: a concurrent checkOut call already moved the booking to
        // CHECKOUT_PENDING between this read and the conditional update (FR-009).
        when(bookingRepository.updateStatusIfCurrent(bookingId, BookingStatus.CHECKOUT_PENDING, BookingStatus.CHECKED_IN))
                .thenReturn(0);
        BookingRequest.CardDetails card = new BookingRequest.CardDetails("4111111111111111", "12/30", "123", "Jane");

        assertThatThrownBy(() -> service.checkOut(bookingId, card)).isInstanceOf(InvalidBookingStateException.class);

        verify(checkoutPaymentAttemptRepository, never()).save(any());
    }

    @Test
    void handlePaymentOutcome_alreadyProcessed_isNoOp() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.HELD, "txn-3");
        when(bookingRepository.findByTransactionId("txn-3")).thenReturn(Optional.of(booking));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(0);

        service.handlePaymentOutcome(event("txn-3", "AUTHORIZED"));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.HELD);
        verify(bookingRepository, never()).save(booking);
    }

    // ── expireCheckoutAttempt (layer 2c, FR-012a — HoldExpiryService's sweep) ───

    @Test
    void expireCheckoutAttempt_pendingAttempt_setsExpiredAndRevertsBookingToCheckedIn() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-sweep-1", CheckoutAttemptStatus.PENDING, "24.50");
        when(checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                attempt.getId(), CheckoutAttemptStatus.EXPIRED, CheckoutAttemptStatus.PENDING)).thenReturn(1);

        service.expireCheckoutAttempt(attempt);

        verify(bookingRepository).updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_IN, BookingStatus.CHECKOUT_PENDING);
    }

    @Test
    void expireCheckoutAttempt_raceLost_alreadyResolved_isNoOp() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-sweep-2", CheckoutAttemptStatus.PENDING, "24.50");
        // Lost the race: a late outcome already resolved this attempt (AUTHORIZED/
        // DECLINED/FAILED) between the sweep's own query and this update.
        when(checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                attempt.getId(), CheckoutAttemptStatus.EXPIRED, CheckoutAttemptStatus.PENDING)).thenReturn(0);

        service.expireCheckoutAttempt(attempt);

        verify(bookingRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }
}
