package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.BookingRequest;
import com.creditcard.booking.controller.dto.CardDetails;
import com.creditcard.booking.controller.dto.ChargeRequest;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingRoomNight;
import com.creditcard.booking.domain.BookingSource;
import com.creditcard.booking.domain.BookingStatus;
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
import com.creditcard.booking.grpc.IdempotencyKeyClientInterceptor;
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
import io.grpc.Status;
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
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** A booking is confirmed synchronously, with its full guest record, the instant it's
 *  created — no card, no payment-core call, no intermediate state (2026-10-31 rework;
 *  see specs/011-charges/research.md §2/§8). The checkout saga this layer built for
 *  extras is unaffected and still collects the room's cost too, now that it's part of
 *  the balance due from the moment of confirmation. Hotel layers never touch card data
 *  themselves — this class only forwards it to transaction-service over the existing
 *  gRPC contract and reacts to the result (CLAUDE.md Working approach). */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final String CURRENCY_CODE = "USD";
    private static final String IDEMPOTENCY_PREFIX = "booking:";
    /** Statuses that count as "paid" toward a booking's balance due (data-model.md's
     *  balance formula) — AUTHORIZED the normal way, or LATE_AUTHORIZED after a
     *  sweep (FR-012b). */
    private static final List<CheckoutAttemptStatus> PAID_CHECKOUT_STATUSES =
            List.of(CheckoutAttemptStatus.AUTHORIZED, CheckoutAttemptStatus.LATE_AUTHORIZED);
    /** InitiateTransaction is retried only on these: the call may not have reached transaction-service. */
    private static final Set<Status.Code> RETRYABLE_CODES = EnumSet.of(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED);
    private static final int MAX_INITIATE_ATTEMPTS = 3;

    private final BookingRepository bookingRepository;
    private final RoomRepository roomRepository;
    private final BookingRoomNightRepository bookingRoomNightRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ChargeRepository chargeRepository;
    private final CheckoutPaymentAttemptRepository checkoutPaymentAttemptRepository;
    private final TransactionServiceGrpc.TransactionServiceBlockingStub transactionServiceBlockingStub;
    private final IdempotencyKeyService idempotencyKeyService;
    private final CheckoutPaymentLedger checkoutPaymentLedger;
    private final String merchantId;

    public BookingService(BookingRepository bookingRepository,
                           RoomRepository roomRepository,
                           BookingRoomNightRepository bookingRoomNightRepository,
                           ProcessedEventRepository processedEventRepository,
                           ChargeRepository chargeRepository,
                           CheckoutPaymentAttemptRepository checkoutPaymentAttemptRepository,
                           TransactionServiceGrpc.TransactionServiceBlockingStub transactionServiceBlockingStub,
                           IdempotencyKeyService idempotencyKeyService,
                           CheckoutPaymentLedger checkoutPaymentLedger,
                           @Value("${booking.merchant-id}") String merchantId) {
        this.bookingRepository = bookingRepository;
        this.roomRepository = roomRepository;
        this.bookingRoomNightRepository = bookingRoomNightRepository;
        this.processedEventRepository = processedEventRepository;
        this.chargeRepository = chargeRepository;
        this.checkoutPaymentAttemptRepository = checkoutPaymentAttemptRepository;
        this.transactionServiceBlockingStub = transactionServiceBlockingStub;
        this.idempotencyKeyService = idempotencyKeyService;
        this.checkoutPaymentLedger = checkoutPaymentLedger;
        this.merchantId = merchantId;
    }

    /**
     * Confirms a booking, honoring an optional Idempotency-Key. A null key behaves exactly
     * like {@link #createConfirmedBooking(BookingRequest)}. With a key, a replay returns the
     * first request's stored booking without creating another one or re-checking the room.
     */
    @Transactional
    public Booking createConfirmedBooking(String idempotencyKey, BookingRequest request) {
        if (idempotencyKey == null) {
            return createConfirmedBooking(request);
        }
        Optional<IdempotencyKeyService.StoredResult> replay = idempotencyKeyService.claimOrReplay(idempotencyKey);
        if (replay.isPresent()) {
            return getById(replay.get().resourceId());
        }
        Booking booking = createConfirmedBooking(request);
        idempotencyKeyService.recordResult(idempotencyKey, booking.getId(), booking.getStatus().name());
        return booking;
    }

    /**
     * One transaction: insert the CONFIRMED booking row (with its full guest record and
     * its rate-locked room cost, research.md §2) and its booking_room_nights rows
     * (research.md §1). A unique-constraint violation on the latter means the room is
     * already taken for an overlapping night — translated to a 409 (FR-007). The only
     * other rejection is a missing required field or an invalid date range, both
     * rejected before any row is written (FR-019/FR-020) — there is no intermediate
     * state to roll back from any more.
     */
    @Transactional
    public Booking createConfirmedBooking(BookingRequest request) {
        return createConfirmedBookingWithSource(request, BookingSource.STAFF);
    }

    /** As {@link #createConfirmedBooking(BookingRequest)}, recording who made the booking
     *  (STAFF, or ONLINE from the public front office). The price and checks are the same. */
    @Transactional
    public Booking createConfirmedBookingWithSource(BookingRequest request, BookingSource source) {
        if (!request.checkOut().isAfter(request.checkIn())) {
            throw new InvalidDateRangeException(request.checkIn(), request.checkOut());
        }
        Room room = roomRepository.findById(request.roomId())
                .orElseThrow(() -> new RoomNotFoundException(request.roomId()));

        long nights = ChronoUnit.DAYS.between(request.checkIn(), request.checkOut());
        BigDecimal roomChargeAmount = room.getNightlyRate().multiply(BigDecimal.valueOf(nights));

        Booking booking = new Booking();
        booking.setRoomId(room.getId());
        booking.setGuestName(request.guestName());
        booking.setGuestPhone(request.guestPhone());
        booking.setGuestEmail(request.guestEmail());
        booking.setVisitingFrom(request.visitingFrom());
        booking.setAdults(request.adults() != null ? request.adults() : 1);
        booking.setChildren(request.children() != null ? request.children() : 0);
        booking.setCheckIn(request.checkIn());
        booking.setCheckOut(request.checkOut());
        booking.setExpectedArrivalTime(request.expectedArrivalTime());
        booking.setExpectedDepartureTime(request.expectedDepartureTime());
        booking.setNotes(request.notes());
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setSource(source);
        booking.setRoomChargeAmount(roomChargeAmount);
        Booking savedBooking = bookingRepository.save(booking);

        List<BookingRoomNight> bookingNights = request.checkIn().datesUntil(request.checkOut())
                .map(date -> new BookingRoomNight(savedBooking.getId(), room.getId(), date))
                .toList();
        try {
            bookingRoomNightRepository.saveAll(bookingNights);
            bookingRoomNightRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new RoomUnavailableException(room.getId(), request.checkIn(), request.checkOut());
        }
        return savedBooking;
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
     * FR-004, data-model.md's balance formula: room cost (the rate-locked
     * {@code roomChargeAmount}, set once at booking creation and never recomputed from
     * the room's current rate — research.md §2) + charges - the amount already paid at
     * booking under the pre-rework model, if any (FR-021; only non-zero for a legacy
     * booking whose {@code legacyRoomPaymentTransactionId} is non-null, research.md §2/
     * §8 — the same figure is added and subtracted, so it's never charged twice) -
     * whatever's already been paid at checkout.
     */
    public BigDecimal getBalanceDue(Booking booking) {
        // The rate-locked quote set once at booking creation (#createConfirmedBooking)
        // — read directly, never recomputed from the room's current rate (research.md
        // §2), so a room-rate change after booking never moves an already-confirmed
        // booking's balance.
        BigDecimal roomCharge = booking.getRoomChargeAmount();
        BigDecimal chargesTotal = chargeRepository.findByBookingIdOrderByCreatedAtAsc(booking.getId()).stream()
                .map(Charge::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal legacyPaidAmount = booking.getLegacyRoomPaymentTransactionId() != null
                ? roomCharge
                : BigDecimal.ZERO;
        BigDecimal paidAtCheckout = checkoutPaymentAttemptRepository
                .findByBookingIdInAndStatusIn(List.of(booking.getId()), PAID_CHECKOUT_STATUSES).stream()
                .map(CheckoutPaymentAttempt::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return roomCharge.add(chargesTotal).subtract(legacyPaidAmount).subtract(paidAtCheckout);
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
     * FR-007/FR-008/FR-009 (layer 2b, extended by layer 2c): checks a CHECKED_IN booking out.
     * Zero balance (FR-013) completes at once, with no card and no payment call. A balance above
     * zero (FR-007) needs a card. The PENDING attempt is committed before any payment call
     * ({@link CheckoutPaymentLedger#begin}), so a crash during the call cannot lose it. The call
     * carries the attempt id as its Idempotency-Key and is retried with that same key, so a call
     * that was processed but whose reply was lost still creates one transaction. No database
     * transaction is open while the payment call is in flight. An attempt that never gets a reply
     * stays PENDING, and the sweep resolves it by key.
     */
    public Booking checkOut(UUID bookingId, CardDetails card) {
        Booking booking = getById(bookingId);
        if (booking.getStatus() != BookingStatus.CHECKED_IN) {
            throw new InvalidBookingStateException(bookingId,
                    "booking must be CHECKED_IN to check out (current: " + booking.getStatus() + ")");
        }

        BigDecimal balanceDue = getBalanceDue(booking);
        if (balanceDue.compareTo(BigDecimal.ZERO) <= 0) {
            checkoutPaymentLedger.completeWithNoBalance(booking);
            return getById(bookingId);
        }
        if (card == null) {
            throw new CardRequiredException(bookingId, balanceDue);
        }

        CheckoutPaymentAttempt attempt = checkoutPaymentLedger.begin(bookingId, balanceDue);
        Optional<String> transactionId = initiateWithRetry(attempt, card);
        if (transactionId.isPresent()) {
            checkoutPaymentLedger.linkTransaction(attempt.getId(), transactionId.get());
            checkoutPaymentLedger.applyParked(transactionId.get());
        }
        return getById(bookingId);
    }

    /**
     * Calls InitiateTransaction with the attempt id as its Idempotency-Key. Only failures where the
     * call may not have reached transaction-service are retried, and the same key makes that retry
     * safe. Returns the transactionId, or empty when no reply arrived. The attempt then stays PENDING.
     */
    private Optional<String> initiateWithRetry(CheckoutPaymentAttempt attempt, CardDetails card) {
        InitiateTransactionRequest grpcRequest = InitiateTransactionRequest.newBuilder()
                .setCardNumber(card.cardNumber())
                .setCardExpiry(card.expiry())
                .setCvv(card.cvv())
                .setCardholderName(card.cardholderName())
                .setAmount(attempt.getAmount().toPlainString())
                .setCurrencyCode(CURRENCY_CODE)
                .setMerchantId(merchantId)
                .build();
        String key = attempt.getId().toString();
        for (int i = 1; i <= MAX_INITIATE_ATTEMPTS; i++) {
            try {
                InitiateTransactionResponse response = IdempotencyKeyClientInterceptor.withKey(key,
                        () -> transactionServiceBlockingStub.withDeadlineAfter(5, TimeUnit.SECONDS)
                                .initiateTransaction(grpcRequest));
                return Optional.of(response.getTransactionId());
            } catch (StatusRuntimeException e) {
                if (!RETRYABLE_CODES.contains(e.getStatus().getCode())) {
                    log.warn("InitiateTransaction for checkout attempt {} failed with {}; not retrying — the attempt "
                            + "stays PENDING until the sweep resolves it by key", key, e.getStatus().getCode());
                    return Optional.empty();
                }
                log.warn("InitiateTransaction attempt {}/{} for checkout attempt {} failed with {}; retrying with the "
                        + "same key", i, MAX_INITIATE_ATTEMPTS, key, e.getStatus().getCode());
            }
        }
        log.warn("InitiateTransaction for checkout attempt {} got no reply after {} attempts; the attempt stays "
                + "PENDING until the sweep resolves it by key", key, MAX_INITIATE_ATTEMPTS);
        return Optional.empty();
    }

    /**
     * FR-012a (Clarifications): the sweep calls this once per stuck attempt that transaction-service
     * says was never charged. The attempt is expired and the booking goes back to CHECKED_IN.
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
     * Consumes transaction.status.updated (via TransactionStatusConsumer). Finds the attempt by
     * transactionId. If no attempt is linked yet, the outcome is parked durably and the consumer acks.
     * It is applied once the attempt links, or when the sweep resolves it, so no delivery is dropped.
     */
    @Transactional
    public void handlePaymentOutcome(TransactionStatusEvent event) {
        TransactionStatusEvent.Payload payload = event.getPayload();
        Optional<CheckoutPaymentAttempt> attempt =
                checkoutPaymentAttemptRepository.findByTransactionId(payload.getTransactionId());
        if (attempt.isEmpty()) {
            checkoutPaymentLedger.park(payload.getTransactionId(), event.getEventId(), payload.getOutcome());
            return;
        }
        if (markProcessedOrSkip(event)) {
            checkoutPaymentLedger.applyOutcome(attempt.get(), payload.getOutcome());
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
}
