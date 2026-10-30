package com.creditcard.booking.controller;

import com.creditcard.booking.controller.dto.BookingCreatedResponse;
import com.creditcard.booking.controller.dto.BookingDetailResponse;
import com.creditcard.booking.controller.dto.BookingRequest;
import com.creditcard.booking.domain.Booking;
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
