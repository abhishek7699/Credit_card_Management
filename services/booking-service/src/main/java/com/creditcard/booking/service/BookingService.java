package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.BookingRequest;
import com.creditcard.booking.controller.dto.CardDetails;
import com.creditcard.booking.controller.dto.ChargeRequest;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingRoomNight;
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
import com.creditcard.booking.kafka.UnknownTransactionException;
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
     * FR-007/FR-008/FR-009 (layer 2b, extended by layer 2c): checks a CHECKED_IN
     * booking out. Zero balance (FR-013) — completes immediately, exactly as before
     * this layer, with no card and no payment-core call. Balance greater than zero
     * (FR-007) — requires a card, atomically moves the booking to CHECKOUT_PENDING
     * (the same conditional-update pattern checkIn uses — this is also what FR-009's
     * "only one checkout attempt in flight" 409 reduces to: a second concurrent call
     * finds the booking no longer CHECKED_IN and gets the same rejection as any other
     * wrong-state attempt), creates a PENDING CheckoutPaymentAttempt for that exact
     * amount, and calls the payment core. A call failure leaves the attempt with no
     * transactionId — swept the same as a never-answered one (CheckoutAttemptSweepService).
     */
    @Transactional
    public Booking checkOut(UUID bookingId, CardDetails card) {
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
                            + "stays CHECKOUT_PENDING; the checkout-attempt sweep resolves it if no outcome ever arrives",
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
     * FR-012a (Clarifications): CheckoutAttemptSweepService's sweep calls this once
     * per stuck attempt, each in its own transaction. The attempt's own conditional
     * update is the race guard — if a late outcome for it already landed (or lands
     * concurrently) via {@link #handlePaymentOutcome}, this becomes a no-op rather
     * than double-applying.
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
     * Consumes transaction.status.updated (via TransactionStatusConsumer). Every
     * outcome this consumer sees is a checkout-attempt outcome now — booking creation
     * no longer has a transactionId space of its own at all (2026-10-31 rework;
     * research.md §5). Looks up the attempt by transactionId; if it's missing, throws
     * so the Kafka error handler retries with bounded backoff rather than marking this
     * delivery processed (research.md §3a) — this is the one case that must NOT be a
     * no-op, since the attempt row and this event can race (the attempt is persisted
     * PENDING, then the gRPC call is made, then the returned transactionId is saved, as
     * separate steps). The outcome matrix itself is in {@link #handleCheckoutOutcome}
     * (FR-010/FR-011/FR-012a/FR-012b/FR-012c).
     */
    @Transactional
    public void handlePaymentOutcome(TransactionStatusEvent event) {
        TransactionStatusEvent.Payload payload = event.getPayload();
        CheckoutPaymentAttempt attempt = checkoutPaymentAttemptRepository
                .findByTransactionId(payload.getTransactionId())
                .orElseThrow(() -> new UnknownTransactionException(payload.getTransactionId()));
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

    /**
     * FR-010/FR-011 (checkout saga): PENDING+AUTHORIZED -> attempt AUTHORIZED, booking
     * CHECKOUT_PENDING -> CHECKED_OUT, room -> DIRTY; PENDING+DECLINED/FAILED ->
     * attempt DECLINED/FAILED, booking back to CHECKED_IN (balance unchanged — staff
     * can retry). FR-012b: EXPIRED+AUTHORIZED (a late outcome after the checkout-attempt
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
