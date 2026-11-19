package com.creditcard.booking.controller;

import com.creditcard.booking.controller.dto.BookingCreatedResponse;
import com.creditcard.booking.controller.dto.BookingDetailResponse;
import com.creditcard.booking.controller.dto.BookingRequest;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.exception.InvalidIdempotencyKeyException;
import com.creditcard.booking.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    /** Optional. Same key + same creation = the first request's stored result, never a duplicate. */
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    /** Matches the idempotency_keys.idempotency_key column width. */
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /** FR-006: the booking is HELD immediately, before any payment outcome is known —
     *  the response never waits on the gRPC call's outcome, only on it being attempted. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingCreatedResponse create(
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody BookingRequest request) {
        BookingService.CreateOutcome outcome = bookingService.holdBooking(normalize(idempotencyKey), request);
        // A replayed request already initiated payment the first time; never charge twice.
        if (outcome.newBooking() != null) {
            bookingService.initiatePayment(outcome.newBooking(), request.card());
        }
        return outcome.response();
    }

    /** A blank header counts as absent, so it behaves exactly like no header at all. */
    private static String normalize(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException(MAX_IDEMPOTENCY_KEY_LENGTH);
        }
        return idempotencyKey;
    }

    @GetMapping("/{id}")
    public BookingDetailResponse get(@PathVariable UUID id) {
        return toDetailResponse(bookingService.getById(id));
    }

    /** FR-005/FR-006 (layer 2b), front-desk only (enforced at the gateway). */
    @PostMapping("/{id}/check-in")
    public BookingDetailResponse checkIn(@PathVariable UUID id) {
        return toDetailResponse(bookingService.checkIn(id));
    }

    /** FR-007/FR-008/FR-009 (layer 2b), front-desk only (enforced at the gateway). */
    @PostMapping("/{id}/check-out")
    public BookingDetailResponse checkOut(@PathVariable UUID id) {
        return toDetailResponse(bookingService.checkOut(id));
    }

    private BookingDetailResponse toDetailResponse(Booking booking) {
        return new BookingDetailResponse(
                booking.getId(),
                booking.getRoomId(),
                booking.getGuestName(),
                booking.getCheckIn(),
                booking.getCheckOut(),
                booking.getStatus(),
                booking.getCancellationReason(),
                booking.getTransactionId());
    }
}
