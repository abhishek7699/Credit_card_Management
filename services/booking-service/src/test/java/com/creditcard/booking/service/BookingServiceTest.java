package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.BookingRequest;
import com.creditcard.booking.controller.dto.CardDetails;
import com.creditcard.booking.controller.dto.ChargeRequest;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
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
import com.creditcard.booking.kafka.UnknownTransactionException;
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

    /**
     * {@code legacyTransactionId} non-null simulates a pre-2026-10-31-rework booking
     * that already paid its room cost at booking time — {@code getBalanceDue} cancels
     * {@code roomChargeAmount} back out for it, leaving the fixture's balance at
     * exactly its charges minus its checkout payments (zero, by default), which is why
     * most tests unrelated to the balance formula itself pass a non-null value here.
     * Tests that specifically exercise the new-booking behavior (room cost counts
     * toward balance, nothing cancels) pass {@code null} instead.
     */
    private static Booking booking(UUID id, BookingStatus status, String legacyTransactionId) {
        Booking booking = new Booking();
        ReflectionTestUtils.setField(booking, "id", id);
        booking.setRoomId(UUID.randomUUID());
        booking.setGuestName("Jane Doe");
        booking.setGuestPhone("+1-555-0100");
        booking.setCheckIn(LocalDate.of(2026, 11, 1));
        booking.setCheckOut(LocalDate.of(2026, 11, 3));
        booking.setStatus(status);
        booking.setLegacyRoomPaymentTransactionId(legacyTransactionId);
        booking.setRoomChargeAmount(new BigDecimal("200.00"));
        return booking;
    }

    // ── createConfirmedBooking (layer 2c rework, US1) ───────────────────────────

    private static BookingRequest minimalRequest(UUID roomId, LocalDate checkIn, LocalDate checkOut) {
        return new BookingRequest(roomId, checkIn, checkOut, "Jane", "+1-555-0100",
                null, null, null, null, null, null, null);
    }

    @Test
    void createConfirmedBooking_checkOutNotAfterCheckIn_throwsInvalidDateRange() {
        LocalDate date = LocalDate.of(2026, 11, 1);
        BookingRequest request = minimalRequest(UUID.randomUUID(), date, date);

        assertThatThrownBy(() -> service.createConfirmedBooking(request))
                .isInstanceOf(InvalidDateRangeException.class);
    }

    @Test
    void createConfirmedBooking_roomNotFound_throwsRoomNotFound() {
        UUID roomId = UUID.randomUUID();
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());
        BookingRequest request = minimalRequest(roomId, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3));

        assertThatThrownBy(() -> service.createConfirmedBooking(request))
                .isInstanceOf(RoomNotFoundException.class);
    }

    @Test
    void createConfirmedBooking_minimalFields_confirmsImmediately_withNoCardAndRateLockedRoomCost() {
        UUID roomId = UUID.randomUUID();
        Room room = room(roomId); // nightlyRate 100.00
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(bookingRepository.save(any())).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
            return b;
        });
        BookingRequest request = minimalRequest(roomId, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3)); // 2 nights

        Booking result = service.createConfirmedBooking(request);

        assertThat(result.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(result.getRoomId()).isEqualTo(roomId);
        assertThat(result.getGuestName()).isEqualTo("Jane");
        assertThat(result.getGuestPhone()).isEqualTo("+1-555-0100");
        assertThat(result.getAdults()).isEqualTo(1); // defaulted, FR-018
        assertThat(result.getChildren()).isEqualTo(0); // defaulted, FR-018
        assertThat(result.getRoomChargeAmount()).isEqualByComparingTo(new BigDecimal("200.00")); // 2 x 100.00, the rate lock
        assertThat(result.getLegacyRoomPaymentTransactionId()).isNull(); // no booking-time payment any more
        verify(bookingRoomNightRepository).saveAll(any());
        verify(bookingRoomNightRepository).flush();
        // No payment-core interaction at all for booking creation (FR-017/FR-020).
        verify(transactionServiceBlockingStub, never()).initiateTransaction(any());
    }

    @Test
    void createConfirmedBooking_fullOptionalFields_allRecorded() {
        UUID roomId = UUID.randomUUID();
        Room room = room(roomId);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        BookingRequest request = new BookingRequest(roomId, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3),
                "Jane", "+1-555-0100", "jane@example.com", "Chicago, IL", 2, 1,
                java.time.LocalTime.of(15, 0), java.time.LocalTime.of(11, 0), "Requested a crib");

        Booking result = service.createConfirmedBooking(request);

        assertThat(result.getGuestEmail()).isEqualTo("jane@example.com");
        assertThat(result.getVisitingFrom()).isEqualTo("Chicago, IL");
        assertThat(result.getAdults()).isEqualTo(2);
        assertThat(result.getChildren()).isEqualTo(1);
        assertThat(result.getExpectedArrivalTime()).isEqualTo(java.time.LocalTime.of(15, 0));
        assertThat(result.getExpectedDepartureTime()).isEqualTo(java.time.LocalTime.of(11, 0));
        assertThat(result.getNotes()).isEqualTo("Requested a crib");
    }

    @Test
    void createConfirmedBooking_nightsConflict_throwsRoomUnavailable() {
        UUID roomId = UUID.randomUUID();
        Room room = room(roomId);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new DataIntegrityViolationException("duplicate key")).when(bookingRoomNightRepository).flush();
        BookingRequest request = minimalRequest(roomId, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3));

        assertThatThrownBy(() -> service.createConfirmedBooking(request))
                .isInstanceOf(RoomUnavailableException.class);
    }

    // ── getById ─────────────────────────────────────────────────────────────────

    @Test
    void getById_found_returnsBooking() {
        UUID id = UUID.randomUUID();
        Booking booking = booking(id, BookingStatus.CONFIRMED, null);
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));

        assertThat(service.getById(id)).isSameAs(booking);
    }

    @Test
    void getById_notFound_throwsBookingNotFound() {
        UUID id = UUID.randomUUID();
        when(bookingRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(BookingNotFoundException.class);
    }

    // ── handlePaymentOutcome (checkout saga only, now the one and only path) ───

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
    void handlePaymentOutcome_noMatchingAttempt_throwsForRetry_andNeverWritesProcessedEvent() {
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handlePaymentOutcome(event("txn-missing", "AUTHORIZED")))
                .isInstanceOf(UnknownTransactionException.class);

        verify(processedEventRepository, never()).insertIfAbsent(any());
    }

    @Test
    void handlePaymentOutcome_alreadyProcessed_isNoOp() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-3", CheckoutAttemptStatus.PENDING, "24.50");
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-3")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(0);

        service.handlePaymentOutcome(event("txn-3", "AUTHORIZED"));

        verify(checkoutPaymentAttemptRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void handlePaymentOutcome_pendingAttempt_authorized_checksOutAndSetsRoomDirty() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = booking(bookingId, BookingStatus.CHECKOUT_PENDING, null);
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-1", CheckoutAttemptStatus.PENDING, "24.50");
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
    void handlePaymentOutcome_pendingAttempt_declined_revertsBookingToCheckedIn() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-2", CheckoutAttemptStatus.PENDING, "24.50");
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-2")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);
        when(checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                attempt.getId(), CheckoutAttemptStatus.DECLINED, CheckoutAttemptStatus.PENDING)).thenReturn(1);

        service.handlePaymentOutcome(event("txn-co-2", "DECLINED"));

        verify(bookingRepository).updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_IN, BookingStatus.CHECKOUT_PENDING);
        verify(roomRepository, never()).setStatus(any(), any());
    }

    @Test
    void handlePaymentOutcome_pendingAttempt_failed_revertsBookingToCheckedIn() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-2f", CheckoutAttemptStatus.PENDING, "24.50");
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-2f")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);
        when(checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                attempt.getId(), CheckoutAttemptStatus.FAILED, CheckoutAttemptStatus.PENDING)).thenReturn(1);

        service.handlePaymentOutcome(event("txn-co-2f", "FAILED"));

        verify(bookingRepository).updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_IN, BookingStatus.CHECKOUT_PENDING);
    }

    /**
     * 013: a checkout whose transaction timed out arrives as FAILED (transaction-service sets reason TIMEOUT).
     * Booking must never record it as paid: no CHECKED_OUT, no AUTHORIZED attempt, room not set DIRTY.
     */
    @Test
    void handlePaymentOutcome_timedOutCheckout_isNeverRecordedAsPaid() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-timeout", CheckoutAttemptStatus.PENDING, "24.50");
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-timeout")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);
        when(checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                attempt.getId(), CheckoutAttemptStatus.FAILED, CheckoutAttemptStatus.PENDING)).thenReturn(1);

        service.handlePaymentOutcome(event("txn-co-timeout", "FAILED"));

        verify(checkoutPaymentAttemptRepository, never()).updateStatusIfCurrent(
                any(), eq(CheckoutAttemptStatus.AUTHORIZED), any());
        verify(bookingRepository, never()).updateStatusIfCurrent(any(), eq(BookingStatus.CHECKED_OUT), any());
        verify(roomRepository, never()).setStatus(any(), any());
        verify(bookingRepository).updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_IN, BookingStatus.CHECKOUT_PENDING);
    }

    @Test
    void handlePaymentOutcome_pendingAttempt_authorizedRaceLost_isNoOp() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-3", CheckoutAttemptStatus.PENDING, "24.50");
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-3")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);
        // Lost the race (e.g. the sweep already moved it to EXPIRED between the read
        // above and this update) -- 0 rows affected.
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
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-5f")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);

        service.handlePaymentOutcome(event("txn-co-5f", "FAILED"));

        verify(checkoutPaymentAttemptRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void handlePaymentOutcome_attemptAlreadyResolved_neitherPendingNorExpired_isNoOp() {
        UUID bookingId = UUID.randomUUID();
        // Already AUTHORIZED (e.g. a different, later Kafka message for the same
        // transactionId) -- status is neither PENDING nor EXPIRED, so neither
        // branch applies.
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-9", CheckoutAttemptStatus.AUTHORIZED, "24.50");
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-9")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);

        service.handlePaymentOutcome(event("txn-co-9", "AUTHORIZED"));

        verify(checkoutPaymentAttemptRepository, never()).updateStatusIfCurrent(any(), any(), any());
        verify(bookingRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void handlePaymentOutcome_pendingAttempt_unrecognizedOutcome_isTrueNoOp() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-6", CheckoutAttemptStatus.PENDING, "24.50");
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-6")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);

        service.handlePaymentOutcome(event("txn-co-6", "PENDING"));

        verify(checkoutPaymentAttemptRepository, never()).updateStatusIfCurrent(any(), any(), any());
        verify(bookingRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void handlePaymentOutcome_pendingAttempt_declinedRaceLost_isNoOp() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-7", CheckoutAttemptStatus.PENDING, "24.50");
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-7")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);
        // Lost the race: the sweep already moved this attempt to EXPIRED between the
        // read above and this update.
        when(checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                attempt.getId(), CheckoutAttemptStatus.DECLINED, CheckoutAttemptStatus.PENDING)).thenReturn(0);

        service.handlePaymentOutcome(event("txn-co-7", "DECLINED"));

        verify(bookingRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void handlePaymentOutcome_expiredAttempt_lateAuthorizedRaceLost_isNoOp() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-8", CheckoutAttemptStatus.EXPIRED, "24.50");
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-8")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);
        // Lost the race: a duplicate late-AUTHORIZED delivery (different eventId,
        // same transactionId) already resolved this attempt first.
        when(checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                attempt.getId(), CheckoutAttemptStatus.LATE_AUTHORIZED, CheckoutAttemptStatus.EXPIRED)).thenReturn(0);

        service.handlePaymentOutcome(event("txn-co-8", "AUTHORIZED"));

        verify(bookingRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void handlePaymentOutcome_duplicateEvent_isNoOp() {
        UUID bookingId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(bookingId, "txn-co-dup", CheckoutAttemptStatus.PENDING, "24.50");
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-co-dup")).thenReturn(Optional.of(attempt));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(0);

        service.handlePaymentOutcome(event("txn-co-dup", "AUTHORIZED"));

        verify(checkoutPaymentAttemptRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    // ── addCharge / getCharges / getBalanceDue (layer 2c) ───────────────────────

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
    void getBalanceDue_legacyBooking_neverDoubleChargedForRoomCostAlreadyPaidAtBooking() {
        UUID bookingId = UUID.randomUUID();
        Booking legacyBooking = booking(bookingId, BookingStatus.CHECKED_IN, "txn-legacy-1"); // non-null marker
        Charge roomService = new Charge();
        roomService.setAmount(new BigDecimal("18.50"));
        Charge minibar = new Charge();
        minibar.setAmount(new BigDecimal("6.00"));
        CheckoutPaymentAttempt paidAttempt = checkoutAttempt(bookingId, "txn-prior", CheckoutAttemptStatus.AUTHORIZED, "10.00");
        when(chargeRepository.findByBookingIdOrderByCreatedAtAsc(bookingId)).thenReturn(List.of(roomService, minibar));
        when(checkoutPaymentAttemptRepository.findByBookingIdInAndStatusIn(List.of(bookingId),
                List.of(CheckoutAttemptStatus.AUTHORIZED, CheckoutAttemptStatus.LATE_AUTHORIZED)))
                .thenReturn(List.of(paidAttempt));

        BigDecimal balance = service.getBalanceDue(legacyBooking);

        // roomChargeAmount (200.00) is added then immediately cancelled by the legacy
        // marker -- only charges (24.50) minus what's already been paid at checkout
        // (10.00) survive. The room cost is never charged a second time.
        assertThat(balance).isEqualByComparingTo(new BigDecimal("14.50"));
    }

    @Test
    void getBalanceDue_newBooking_noLegacyMarker_roomCostCountsTowardBalance() {
        UUID bookingId = UUID.randomUUID();
        Booking newBooking = booking(bookingId, BookingStatus.CONFIRMED, null); // no legacy marker
        when(chargeRepository.findByBookingIdOrderByCreatedAtAsc(bookingId)).thenReturn(List.of());
        when(checkoutPaymentAttemptRepository.findByBookingIdInAndStatusIn(any(), any())).thenReturn(List.of());

        BigDecimal balance = service.getBalanceDue(newBooking);

        // FR-004/FR-021: a freshly confirmed booking's balance starts at its full room
        // cost -- nothing cancels it, since this booking never paid at booking time.
        assertThat(balance).isEqualByComparingTo(new BigDecimal("200.00"));
    }

    @Test
    void getBalanceDue_roomRateChangedAfterBooking_balanceUnaffected() {
        UUID roomId = UUID.randomUUID();
        Room room = room(roomId); // nightlyRate 100.00 at booking time
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(bookingRepository.save(any())).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
            return b;
        });
        BookingRequest request = minimalRequest(roomId, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3)); // 2 nights
        Booking confirmed = service.createConfirmedBooking(request);
        assertThat(confirmed.getRoomChargeAmount()).isEqualByComparingTo(new BigDecimal("200.00"));

        // The room's rate changes after the booking was confirmed.
        room.setNightlyRate(new BigDecimal("500.00"));
        when(chargeRepository.findByBookingIdOrderByCreatedAtAsc(confirmed.getId())).thenReturn(List.of());
        when(checkoutPaymentAttemptRepository.findByBookingIdInAndStatusIn(any(), any())).thenReturn(List.of());

        BigDecimal balance = service.getBalanceDue(confirmed);

        // Still the rate-locked 200.00 -- never recomputed from the room's new,
        // current rate (research.md §2).
        assertThat(balance).isEqualByComparingTo(new BigDecimal("200.00"));
        // Exactly the one findById call from creation above -- getBalanceDue itself
        // never looks up the room at all.
        verify(roomRepository, org.mockito.Mockito.times(1)).findById(roomId);
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
        Booking checkedOut = booking(bookingId, BookingStatus.CHECKED_OUT, null);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(checkedOut));

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
    void checkOut_newBooking_zeroRateRoom_completesImmediately_noCardNoPaymentCoreCall() {
        // spec.md US5: now the only way to reach a zero balance is a zero-rate
        // room -- a new booking's room cost alone otherwise always creates one.
        UUID roomId = UUID.randomUUID();
        Room zeroRateRoom = room(roomId);
        zeroRateRoom.setNightlyRate(BigDecimal.ZERO);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(zeroRateRoom));
        when(bookingRepository.save(any())).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
            return b;
        });
        BookingRequest request = minimalRequest(roomId, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3));
        Booking confirmed = service.createConfirmedBooking(request);
        assertThat(confirmed.getRoomChargeAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        UUID bookingId = confirmed.getId();
        confirmed.setStatus(BookingStatus.CHECKED_IN); // check-in itself is unaffected by this test
        Booking checkedOut = booking(bookingId, BookingStatus.CHECKED_OUT, null);
        checkedOut.setRoomId(roomId);
        checkedOut.setRoomChargeAmount(BigDecimal.ZERO);
        when(bookingRepository.findById(bookingId))
                .thenReturn(Optional.of(confirmed))
                .thenReturn(Optional.of(checkedOut));
        when(chargeRepository.findByBookingIdOrderByCreatedAtAsc(bookingId)).thenReturn(List.of());
        when(checkoutPaymentAttemptRepository.findByBookingIdInAndStatusIn(any(), any())).thenReturn(List.of());
        when(bookingRepository.updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_OUT, BookingStatus.CHECKED_IN))
                .thenReturn(1);

        Booking result = service.checkOut(bookingId, null);

        assertThat(result.getStatus()).isEqualTo(BookingStatus.CHECKED_OUT);
        verify(roomRepository).setStatus(roomId, RoomStatus.DIRTY);
        verify(checkoutPaymentAttemptRepository, never()).save(any());
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
    // grpc-java's AbstractStub, so a bare Mockito mock NPEs on it. Only the branches
    // that return *before* reaching the gRPC call belong here.

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
        CardDetails card = new CardDetails("4111111111111111", "12/30", "123", "Jane");

        assertThatThrownBy(() -> service.checkOut(bookingId, card)).isInstanceOf(InvalidBookingStateException.class);

        verify(checkoutPaymentAttemptRepository, never()).save(any());
    }

    // ── expireCheckoutAttempt (layer 2c, FR-012a — CheckoutAttemptSweepService's sweep) ───

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
