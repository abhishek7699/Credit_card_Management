package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.BookingRequest;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingRoomNight;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.CancellationReason;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.domain.RoomStatus;
import com.creditcard.booking.exception.BookingNotFoundException;
import com.creditcard.booking.exception.InvalidBookingStateException;
import com.creditcard.booking.exception.InvalidDateRangeException;
import com.creditcard.booking.exception.RoomNotFoundException;
import com.creditcard.booking.exception.RoomUnavailableException;
import com.creditcard.booking.kafka.BookingNotFoundForTransactionException;
import com.creditcard.booking.kafka.event.TransactionStatusEvent;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.BookingRoomNightRepository;
import com.creditcard.booking.repository.ProcessedEventRepository;
import com.creditcard.booking.repository.RoomRepository;
import com.creditcard.transaction.grpc.v1.InitiateTransactionRequest;
import com.creditcard.transaction.grpc.v1.InitiateTransactionResponse;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Saga orchestration: HELD -> charge -> CONFIRMED/CANCELLED. Hotel layers never touch
 *  card data themselves — this class only forwards it to transaction-service over the
 *  existing gRPC contract and reacts to the result (CLAUDE.md Working approach). */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final String CURRENCY_CODE = "USD";
    private static final String IDEMPOTENCY_PREFIX = "booking:";
    private static final String OUTCOME_AUTHORIZED = "AUTHORIZED";
    private static final String OUTCOME_DECLINED = "DECLINED";
    private static final String OUTCOME_FAILED = "FAILED";

    private final BookingRepository bookingRepository;
    private final RoomRepository roomRepository;
    private final BookingRoomNightRepository bookingRoomNightRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final TransactionServiceGrpc.TransactionServiceBlockingStub transactionServiceBlockingStub;
    private final String merchantId;

    public BookingService(BookingRepository bookingRepository,
                           RoomRepository roomRepository,
                           BookingRoomNightRepository bookingRoomNightRepository,
                           ProcessedEventRepository processedEventRepository,
                           TransactionServiceGrpc.TransactionServiceBlockingStub transactionServiceBlockingStub,
                           @Value("${booking.merchant-id}") String merchantId) {
        this.bookingRepository = bookingRepository;
        this.roomRepository = roomRepository;
        this.bookingRoomNightRepository = bookingRoomNightRepository;
        this.processedEventRepository = processedEventRepository;
        this.transactionServiceBlockingStub = transactionServiceBlockingStub;
        this.merchantId = merchantId;
    }

    /**
     * One transaction: insert the HELD booking row and its booking_room_nights rows
     * (research.md §1). A unique-constraint violation on the latter means the room is
     * already taken for an overlapping night — translated to a 409 (FR-007).
     */
    @Transactional
    public Booking createHeldBooking(BookingRequest request) {
        if (!request.checkOut().isAfter(request.checkIn())) {
            throw new InvalidDateRangeException(request.checkIn(), request.checkOut());
        }
        Room room = roomRepository.findById(request.roomId())
                .orElseThrow(() -> new RoomNotFoundException(request.roomId()));

        Booking booking = new Booking();
        booking.setRoomId(room.getId());
        booking.setGuestName(request.guestName());
        booking.setCheckIn(request.checkIn());
        booking.setCheckOut(request.checkOut());
        booking.setStatus(BookingStatus.HELD);
        Booking savedBooking = bookingRepository.save(booking);

        List<BookingRoomNight> nights = request.checkIn().datesUntil(request.checkOut())
                .map(date -> new BookingRoomNight(savedBooking.getId(), room.getId(), date))
                .toList();
        try {
            bookingRoomNightRepository.saveAll(nights);
            bookingRoomNightRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new RoomUnavailableException(room.getId(), request.checkIn(), request.checkOut());
        }
        return savedBooking;
    }

    /**
     * Calls transaction-service with a 5s deadline (research.md §2, Key Design
     * Decision #3). On DEADLINE_EXCEEDED/UNAVAILABLE, the already-HELD booking is left
     * HELD with no transactionId set — hold expiry (not a retry here) resolves it.
     */
    public void initiatePayment(Booking booking, BookingRequest.CardDetails card) {
        Room room = roomRepository.findById(booking.getRoomId())
                .orElseThrow(() -> new RoomNotFoundException(booking.getRoomId()));
        long nights = ChronoUnit.DAYS.between(booking.getCheckIn(), booking.getCheckOut());
        BigDecimal amount = room.getNightlyRate().multiply(BigDecimal.valueOf(nights));

        InitiateTransactionRequest grpcRequest = InitiateTransactionRequest.newBuilder()
                .setCardNumber(card.cardNumber())
                .setCardExpiry(card.expiry())
                .setCvv(card.cvv())
                .setCardholderName(card.cardholderName())
                .setAmount(amount.toPlainString())
                .setCurrencyCode(CURRENCY_CODE)
                .setMerchantId(merchantId)
                .build();

        try {
            InitiateTransactionResponse response = transactionServiceBlockingStub
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .initiateTransaction(grpcRequest);
            booking.setTransactionId(response.getTransactionId());
            bookingRepository.save(booking);
        } catch (StatusRuntimeException e) {
            log.warn("InitiateTransaction call failed for booking {} (status={}) — booking stays HELD; "
                            + "hold expiry resolves it if payment never completes",
                    booking.getId(), e.getStatus().getCode());
        }
    }

    public Booking getById(UUID id) {
        return bookingRepository.findById(id).orElseThrow(() -> new BookingNotFoundException(id));
    }

    /**
     * FR-005/FR-006 (layer 2b): checks a CONFIRMED booking in, transitioning its room
     * VACANT_CLEAN -> OCCUPIED and the booking CONFIRMED -> CHECKED_IN. The room is
     * updated before the booking, both via the conditional-update pattern
     * (research.md §2) — a fixed lock order, shared with checkOut, rules out a
     * cross-operation deadlock (plan.md Key Design Decision #3). Either conditional
     * update affecting zero rows aborts the whole operation (transaction rolls back).
     */
    @Transactional
    public Booking checkIn(UUID bookingId) {
        Booking booking = getById(bookingId);
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new InvalidBookingStateException(bookingId,
                    "booking must be CONFIRMED to check in (current: " + booking.getStatus() + ")");
        }

        int roomUpdated = roomRepository.updateStatusIfCurrent(
                booking.getRoomId(), RoomStatus.OCCUPIED, RoomStatus.VACANT_CLEAN);
        if (roomUpdated == 0) {
            throw new InvalidBookingStateException(bookingId, "room is not VACANT_CLEAN");
        }

        int bookingUpdated = bookingRepository.updateStatusIfCurrent(
                bookingId, BookingStatus.CHECKED_IN, BookingStatus.CONFIRMED);
        if (bookingUpdated == 0) {
            throw new InvalidBookingStateException(bookingId, "booking is no longer CONFIRMED");
        }

        return getById(bookingId);
    }

    /**
     * FR-007/FR-008 (layer 2b): checks a CHECKED_IN booking out, transitioning it to
     * CHECKED_OUT and its room to DIRTY — no payment of any kind (FR-009). Unlike
     * checkIn, the booking's own conditional update runs first: it is the only
     * gated step here, so the room (whose update is unconditional — DIRTY applies
     * regardless of the room's own current status, per spec.md's edge case: booking
     * state is authoritative for this transition) is only ever touched once that
     * precondition is confirmed to have genuinely held.
     */
    @Transactional
    public Booking checkOut(UUID bookingId) {
        Booking booking = getById(bookingId);
        if (booking.getStatus() != BookingStatus.CHECKED_IN) {
            throw new InvalidBookingStateException(bookingId,
                    "booking must be CHECKED_IN to check out (current: " + booking.getStatus() + ")");
        }

        int bookingUpdated = bookingRepository.updateStatusIfCurrent(
                bookingId, BookingStatus.CHECKED_OUT, BookingStatus.CHECKED_IN);
        if (bookingUpdated == 0) {
            throw new InvalidBookingStateException(bookingId, "booking is no longer CHECKED_IN");
        }

        roomRepository.setStatus(booking.getRoomId(), RoomStatus.DIRTY);

        return getById(bookingId);
    }

    /**
     * Cancels a booking: status + cancellation reason update and the deletion of its
     * booking_room_nights rows in the SAME transaction (Key Design Decision #1,
     * plan.md) — the room is immediately re-searchable, never left blocked by a stale
     * row. Used for a declined/failed payment (FR-011) and for hold expiry (FR-015).
     */
    @Transactional
    public void cancel(Booking booking, CancellationReason reason) {
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancellationReason(reason);
        bookingRepository.save(booking);
        bookingRoomNightRepository.deleteByBookingId(booking.getId());
    }

    /**
     * Consumes transaction.status.updated (via TransactionStatusConsumer). Looks up the
     * booking by transactionId first: if none matches, throws so the Kafka error
     * handler retries with bounded backoff rather than marking this delivery processed
     * (research.md §3a) — this is the one case that must NOT be a no-op.
     *
     * <p>Full handling matrix (contracts/events.md): HELD+AUTHORIZED -> CONFIRMED
     * (FR-010); HELD+DECLINED/FAILED -> CANCELLED, room released (FR-011); a CANCELLED
     * booking whose reason is HOLD_EXPIRED receiving a late AUTHORIZED outcome stays
     * CANCELLED — never reverses — only its reason changes to PAYMENT_AFTER_EXPIRY,
     * with a WARN logged for manual refund (FR-019, FR-023, no reverse transitions);
     * every other combination (CONFIRMED already reached; CANCELLED for any other
     * reason; DECLINED/FAILED for an already non-HELD booking) is a no-op (FR-021).
     */
    @Transactional
    public void handlePaymentOutcome(TransactionStatusEvent event) {
        TransactionStatusEvent.Payload payload = event.getPayload();
        Booking booking = bookingRepository.findByTransactionId(payload.getTransactionId())
                .orElseThrow(() -> new BookingNotFoundForTransactionException(payload.getTransactionId()));

        String idempotencyKey = IDEMPOTENCY_PREFIX + event.getEventType() + ":" + event.getEventId();
        int inserted = processedEventRepository.insertIfAbsent(idempotencyKey);
        if (inserted == 0) {
            log.debug("Duplicate transaction.status.updated event, skipping: {}", idempotencyKey);
            return;
        }

        String outcome = payload.getOutcome();
        if (booking.getStatus() == BookingStatus.HELD) {
            if (OUTCOME_AUTHORIZED.equals(outcome)) {
                booking.setStatus(BookingStatus.CONFIRMED);
                bookingRepository.save(booking);
            } else if (OUTCOME_DECLINED.equals(outcome) || OUTCOME_FAILED.equals(outcome)) {
                cancel(booking, OUTCOME_DECLINED.equals(outcome)
                        ? CancellationReason.PAYMENT_DECLINED
                        : CancellationReason.PAYMENT_FAILED);
            }
        } else if (booking.getStatus() == BookingStatus.CANCELLED
                && booking.getCancellationReason() == CancellationReason.HOLD_EXPIRED
                && OUTCOME_AUTHORIZED.equals(outcome)) {
            booking.setCancellationReason(CancellationReason.PAYMENT_AFTER_EXPIRY);
            bookingRepository.save(booking);
            log.warn("AUTHORIZED outcome arrived for booking {} (transactionId={}) after it was already "
                            + "cancelled by hold expiry — staying CANCELLED (no reverse transition); "
                            + "manual refund required",
                    booking.getId(), payload.getTransactionId());
        }
    }
}
