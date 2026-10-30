package com.creditcard.booking.controller;

import com.creditcard.booking.controller.dto.BookingCreatedResponse;
import com.creditcard.booking.controller.dto.BookingDetailResponse;
import com.creditcard.booking.controller.dto.BookingRequest;
import com.creditcard.booking.controller.dto.ChargeRequest;
import com.creditcard.booking.controller.dto.ChargeResponse;
import com.creditcard.booking.controller.dto.CheckOutRequest;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.Charge;
import com.creditcard.booking.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /** FR-006: the booking is HELD immediately, before any payment outcome is known —
     *  the response never waits on the gRPC call's outcome, only on it being attempted. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingCreatedResponse create(@Valid @RequestBody BookingRequest request) {
        Booking booking = bookingService.createHeldBooking(request);
        bookingService.initiatePayment(booking, request.card());
        return new BookingCreatedResponse(booking.getId(), booking.getStatus());
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

    /**
     * FR-007/FR-008/FR-009 (layer 2b, extended by layer 2c). {@code request} is
     * entirely optional — no body, or {@code {}}, is valid for a zero-balance
     * checkout (contracts/api.md); whether a card is actually required is a
     * business-logic decision made in BookingService, not bean validation here.
     */
    @PostMapping("/{id}/check-out")
    public BookingDetailResponse checkOut(@PathVariable UUID id,
                                           @Valid @RequestBody(required = false) CheckOutRequest request) {
        BookingRequest.CardDetails card = request != null ? request.card() : null;
        return toDetailResponse(bookingService.checkOut(id, card));
    }

    /** FR-001/FR-002 (layer 2c), front-desk only (enforced at the gateway). */
    @PostMapping("/{id}/charges")
    @ResponseStatus(HttpStatus.CREATED)
    public ChargeResponse addCharge(@PathVariable UUID id, @Valid @RequestBody ChargeRequest request) {
        return toChargeResponse(bookingService.addCharge(id, request));
    }

    /** FR-003 (layer 2c), front-desk or housekeeping (enforced at the gateway). */
    @GetMapping("/{id}/charges")
    public List<ChargeResponse> getCharges(@PathVariable UUID id) {
        return bookingService.getCharges(id).stream().map(this::toChargeResponse).toList();
    }

    private BookingDetailResponse toDetailResponse(Booking booking) {
        List<ChargeResponse> charges = bookingService.getCharges(booking.getId()).stream()
                .map(this::toChargeResponse)
                .toList();
        return new BookingDetailResponse(
                booking.getId(),
                booking.getRoomId(),
                booking.getGuestName(),
                booking.getCheckIn(),
                booking.getCheckOut(),
                booking.getStatus(),
                booking.getCancellationReason(),
                booking.getTransactionId(),
                charges,
                bookingService.getBalanceDue(booking));
    }

    private ChargeResponse toChargeResponse(Charge charge) {
        return new ChargeResponse(charge.getId(), charge.getType(), charge.getDescription(),
                charge.getAmount(), charge.getCreatedAt());
    }
}
