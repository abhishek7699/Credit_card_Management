package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.BookingRequest;
import com.creditcard.booking.controller.dto.ChargeRequest;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingRoomNight;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.CancellationReason;
import com.creditcard.booking.domain.Charge;
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
    /** Statuses that count as "paid" toward a booking's balance due (data-model.md's
     *  balance formula) — AUTHORIZED the normal way, or LATE_AUTHORIZED after a
     *  sweep (FR-012b). */
    private static final List<CheckoutAttemptStatus> PAID_CHECKOUT_STATUSES =
            List.of(CheckoutAttemptStatus.AUTHORIZED, CheckoutAttemptStatus.LATE_AUTHORIZED);

    private final BookingRepository bookingRepository;
    private final RoomRepository roomRepository;
    private final BookingRoomNightRepository bookingRoomNightRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ChargeRepository chargeRepository;
    private final CheckoutPaymentAttemptRepository checkoutPaymentAttemptRepository;
    private final TransactionServiceGrpc.TransactionServiceBlockingStub transactionServiceBlockingStub;
    private final String merchantId;

    public BookingService(BookingRepository bookingRepository,
                           RoomRepository roomRepository,
                           BookingRoomNightRepository bookingRoomNightRepository,
                           ProcessedEventRepository processedEventRepository,
                           ChargeRepository chargeRepository,
                           CheckoutPaymentAttemptRepository checkoutPaymentAttemptRepository,
                           TransactionServiceGrpc.TransactionServiceBlockingStub transactionServiceBlockingStub,
                           @Value("${booking.merchant-id}") String merchantId) {
        this.bookingRepository = bookingRepository;
        this.roomRepository = roomRepository;
        this.bookingRoomNightRepository = bookingRoomNightRepository;
        this.processedEventRepository = processedEventRepository;
        this.chargeRepository = chargeRepository;
        this.checkoutPaymentAttemptRepository = checkoutPaymentAttemptRepository;
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
            booking.setRoomChargeAmount(amount);
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

    /** FR-001/FR-002: staff add a charge to a booking only while it's CHECKED_IN. */
    @Transactional
    public Charge addCharge(UUID bookingId, ChargeRequest request) {
        Booking booking = getById(bookingId);
        if (booking.getStatus() != BookingStatus.CHECKED_IN) {
            throw new InvalidBookingStateException(bookingId,
                    "booking must be CHECKED_IN to add a charge (current: " + booking.getStatus() + ")");
        }
        Charge charge = new Charge();
        charge.setBookingId(bookingId);
        charge.setType(request.type());
        charge.setDescription(request.description());
        charge.setAmount(request.amount());
        return chargeRepository.save(charge);
    }

    /** FR-003: line items, oldest first. */
    public List<Charge> getCharges(UUID bookingId) {
        return chargeRepository.findByBookingIdOrderByCreatedAtAsc(bookingId);
    }

    /**
     * FR-004, data-model.md's balance formula: room nights x nightly rate (the
     * {@code roomChargeAmount} term) + charges - the amount already paid at booking
     * (the same {@code roomChargeAmount} term again) - whatever's already been paid at
     * checkout. The two {@code roomChargeAmount} terms always cancel by construction
     * (a booking never reaches CHECKED_IN without its room charge already having been
     * authorized) — written literally, matching the spec, rather than pre-simplified,
     * so {@link #resolveRoomChargeAmount} stays a real, visible, independently-tested
     * code path rather than dead code one refactor away from a silent regression.
     */
    public BigDecimal getBalanceDue(Booking booking) {
        BigDecimal roomCharge = resolveRoomChargeAmount(booking);
        BigDecimal chargesTotal = chargeRepository.findByBookingIdOrderByCreatedAtAsc(booking.getId()).stream()
                .map(Charge::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paidAtCheckout = checkoutPaymentAttemptRepository
                .findByBookingIdInAndStatusIn(List.of(booking.getId()), PAID_CHECKOUT_STATUSES).stream()
                .map(CheckoutPaymentAttempt::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return roomCharge.add(chargesTotal).subtract(roomCharge).subtract(paidAtCheckout);
    }

    /**
     * {@code roomChargeAmount} is null only for a booking that reached CHECKED_IN
     * before that column existed (pre-V6 data, plan.md Key Design Decision #1) — fall
     * back to the same nights x nightly-rate formula {@link #initiatePayment} itself
     * used at booking time, recomputed from the room's current rate.
     */
    private BigDecimal resolveRoomChargeAmount(Booking booking) {
        if (booking.getRoomChargeAmount() != null) {
            return booking.getRoomChargeAmount();
        }
        Room room = roomRepository.findById(booking.getRoomId())
                .orElseThrow(() -> new RoomNotFoundException(booking.getRoomId()));
        long nights = ChronoUnit.DAYS.between(booking.getCheckIn(), booking.getCheckOut());
        return room.getNightlyRate().multiply(BigDecimal.valueOf(nights));
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
     * FR-007/FR-008/FR-009 (layer 2b, extended by layer 2c): checks a CHECKED_IN
     * booking out. Zero balance (FR-013) — completes immediately, exactly as before
     * this layer, with no card and no payment-core call. Balance greater than zero
     * (FR-007) — requires a card, atomically moves the booking to CHECKOUT_PENDING
     * (the same conditional-update pattern checkIn uses — this is also what FR-009's
     * "only one checkout attempt in flight" 409 reduces to: a second concurrent call
     * finds the booking no longer CHECKED_IN and gets the same rejection as any other
     * wrong-state attempt), creates a PENDING CheckoutPaymentAttempt for that exact
     * amount, and calls the payment core. A call failure leaves the attempt with no
     * transactionId — swept the same as a never-answered one (HoldExpiryService).
     */
    @Transactional
    public Booking checkOut(UUID bookingId, BookingRequest.CardDetails card) {
        Booking booking = getById(bookingId);
        if (booking.getStatus() != BookingStatus.CHECKED_IN) {
            throw new InvalidBookingStateException(bookingId,
                    "booking must be CHECKED_IN to check out (current: " + booking.getStatus() + ")");
        }

        BigDecimal balanceDue = getBalanceDue(booking);
        if (balanceDue.compareTo(BigDecimal.ZERO) <= 0) {
            return completeCheckoutWithNoBalance(booking);
        }
        if (card == null) {
            throw new CardRequiredException(bookingId, balanceDue);
        }

        int bookingUpdated = bookingRepository.updateStatusIfCurrent(
                bookingId, BookingStatus.CHECKOUT_PENDING, BookingStatus.CHECKED_IN);
        if (bookingUpdated == 0) {
            throw new InvalidBookingStateException(bookingId,
                    "booking is no longer CHECKED_IN (a checkout attempt may already be in progress)");
        }

        CheckoutPaymentAttempt attempt = new CheckoutPaymentAttempt();
        attempt.setBookingId(bookingId);
        attempt.setAmount(balanceDue);
        attempt.setStatus(CheckoutAttemptStatus.PENDING);
        attempt = checkoutPaymentAttemptRepository.save(attempt);

        InitiateTransactionRequest grpcRequest = InitiateTransactionRequest.newBuilder()
                .setCardNumber(card.cardNumber())
                .setCardExpiry(card.expiry())
                .setCvv(card.cvv())
                .setCardholderName(card.cardholderName())
                .setAmount(balanceDue.toPlainString())
                .setCurrencyCode(CURRENCY_CODE)
                .setMerchantId(merchantId)
                .build();
        try {
            InitiateTransactionResponse response = transactionServiceBlockingStub
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .initiateTransaction(grpcRequest);
            attempt.setTransactionId(response.getTransactionId());
            checkoutPaymentAttemptRepository.save(attempt);
        } catch (StatusRuntimeException e) {
            log.warn("InitiateTransaction call failed for checkout attempt {} (booking {}, status={}) — booking "
                            + "stays CHECKOUT_PENDING; the hold-expiry sweep resolves it if no outcome ever arrives",
                    attempt.getId(), bookingId, e.getStatus().getCode());
        }

        return getById(bookingId);
    }

    /** The original, payment-free checkout (FR-007/FR-008, unchanged since layer 2b) —
     *  reached only when {@link #getBalanceDue} is zero or less. */
    private Booking completeCheckoutWithNoBalance(Booking booking) {
        int bookingUpdated = bookingRepository.updateStatusIfCurrent(
                booking.getId(), BookingStatus.CHECKED_OUT, BookingStatus.CHECKED_IN);
        if (bookingUpdated == 0) {
            throw new InvalidBookingStateException(booking.getId(), "booking is no longer CHECKED_IN");
        }

        roomRepository.setStatus(booking.getRoomId(), RoomStatus.DIRTY);

        return getById(booking.getId());
    }

    /**
     * FR-012a (Clarifications): HoldExpiryService's second sweep calls this once per
     * stuck attempt, each in its own transaction, mirroring {@link #cancel}'s own
     * per-booking isolation. The attempt's own conditional update is the race guard —
     * if a late outcome for it already landed (or lands concurrently) via
     * {@link #handlePaymentOutcome}, this becomes a no-op rather than double-applying.
     */
    @Transactional
    public void expireCheckoutAttempt(CheckoutPaymentAttempt attempt) {
        int attemptUpdated = checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                attempt.getId(), CheckoutAttemptStatus.EXPIRED, CheckoutAttemptStatus.PENDING);
        if (attemptUpdated == 1) {
            bookingRepository.updateStatusIfCurrent(
                    attempt.getBookingId(), BookingStatus.CHECKED_IN, BookingStatus.CHECKOUT_PENDING);
        }
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
     * booking by transactionId first; on a miss, falls back to a CheckoutPaymentAttempt
     * lookup by the same transactionId (data-model.md Key Design Decision #3 — a
     * checkout charge reuses this exact saga's own transactionId space) before giving
     * up. If neither matches, throws so the Kafka error handler retries with bounded
     * backoff rather than marking this delivery processed (research.md §3a) — this is
     * the one case that must NOT be a no-op.
     *
     * <p>Full handling matrix for the original room-charge saga (contracts/events.md):
     * HELD+AUTHORIZED -> CONFIRMED (FR-010); HELD+DECLINED/FAILED -> CANCELLED, room
     * released (FR-011); a CANCELLED booking whose reason is HOLD_EXPIRED receiving a
     * late AUTHORIZED outcome stays CANCELLED — never reverses — only its reason
     * changes to PAYMENT_AFTER_EXPIRY, with a WARN logged for manual refund (FR-019,
     * FR-023, no reverse transitions); every other combination (CONFIRMED already
     * reached; CANCELLED for any other reason; DECLINED/FAILED for an already non-HELD
     * booking) is a no-op (FR-021). The checkout saga's own matrix is in
     * {@link #handleCheckoutOutcome} (FR-010/FR-011/FR-012a/FR-012b/FR-012c).
     */
    @Transactional
    public void handlePaymentOutcome(TransactionStatusEvent event) {
        TransactionStatusEvent.Payload payload = event.getPayload();
        var bookingOpt = bookingRepository.findByTransactionId(payload.getTransactionId());
        if (bookingOpt.isPresent()) {
            if (markProcessedOrSkip(event)) {
                handleRoomChargeOutcome(bookingOpt.get(), payload);
            }
            return;
        }

        // The idempotency mark happens only once a match (booking or attempt) is
        // confirmed to exist — never before, and never for the not-found case below.
        // Marking it earlier would let a legitimate retry (the booking/attempt row
        // simply hasn't landed yet when this event first arrives) see itself as
        // "already processed" and silently never apply once the row does appear.
        CheckoutPaymentAttempt attempt = checkoutPaymentAttemptRepository
                .findByTransactionId(payload.getTransactionId())
                .orElseThrow(() -> new BookingNotFoundForTransactionException(payload.getTransactionId()));
        if (markProcessedOrSkip(event)) {
            handleCheckoutOutcome(attempt, payload);
        }
    }

    private boolean markProcessedOrSkip(TransactionStatusEvent event) {
        String idempotencyKey = IDEMPOTENCY_PREFIX + event.getEventType() + ":" + event.getEventId();
        int inserted = processedEventRepository.insertIfAbsent(idempotencyKey);
        if (inserted == 0) {
            log.debug("Duplicate transaction.status.updated event, skipping: {}", idempotencyKey);
            return false;
        }
        return true;
    }

    private void handleRoomChargeOutcome(Booking booking, TransactionStatusEvent.Payload payload) {
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

    /**
     * FR-010/FR-011 (checkout saga): PENDING+AUTHORIZED -> attempt AUTHORIZED, booking
     * CHECKOUT_PENDING -> CHECKED_OUT, room -> DIRTY; PENDING+DECLINED/FAILED ->
     * attempt DECLINED/FAILED, booking back to CHECKED_IN (balance unchanged — staff
     * can retry). FR-012b: EXPIRED+AUTHORIZED (a late outcome after the hold-expiry
     * sweep already reverted the booking) -> attempt LATE_AUTHORIZED, recorded as a
     * payment (reduces the balance {@link #getBalanceDue} returns next), booking stays
     * CHECKED_IN, a WARN logged — never silently dropped (FR-012b's own instruction).
     * FR-012c: EXPIRED+DECLINED/FAILED is a true no-op, nothing to record. Every
     * transition is conditional on the attempt's current status (research.md §2) so a
     * concurrent sweep and a late outcome can never double-apply.
     */
    private void handleCheckoutOutcome(CheckoutPaymentAttempt attempt, TransactionStatusEvent.Payload payload) {
        String outcome = payload.getOutcome();
        if (attempt.getStatus() == CheckoutAttemptStatus.PENDING) {
            if (OUTCOME_AUTHORIZED.equals(outcome)) {
                int attemptUpdated = checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                        attempt.getId(), CheckoutAttemptStatus.AUTHORIZED, CheckoutAttemptStatus.PENDING);
                if (attemptUpdated == 1) {
                    Booking booking = getById(attempt.getBookingId());
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
        // Any other combination (already AUTHORIZED/DECLINED/FAILED/LATE_AUTHORIZED,
        // or EXPIRED+DECLINED/FAILED) is a true no-op — nothing to record (FR-012c).
    }
}
