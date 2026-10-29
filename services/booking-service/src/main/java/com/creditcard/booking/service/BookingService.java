package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.BookingRequest;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingRoomNight;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.exception.BookingNotFoundException;
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
     * Consumes transaction.status.updated (via TransactionStatusConsumer). Looks up the
     * booking by transactionId first: if none matches, throws so the Kafka error
     * handler retries with bounded backoff rather than marking this delivery processed
     * (research.md §3a) — this is the one case that must NOT be a no-op.
     *
     * <p>This phase implements only the HELD+AUTHORIZED -> CONFIRMED path (FR-010).
     * HELD+DECLINED/FAILED (US2) and CANCELLED+AUTHORIZED/PAYMENT_AFTER_EXPIRY (US3)
     * are added in later phases.
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

        if (booking.getStatus() == BookingStatus.HELD && OUTCOME_AUTHORIZED.equals(payload.getOutcome())) {
            booking.setStatus(BookingStatus.CONFIRMED);
            bookingRepository.save(booking);
        }
    }
}
