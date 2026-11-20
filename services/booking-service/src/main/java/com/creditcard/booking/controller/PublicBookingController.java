package com.creditcard.booking.controller;

import com.creditcard.booking.controller.dto.PublicBookingRequest;
import com.creditcard.booking.controller.dto.PublicAvailabilityResponse;
import com.creditcard.booking.controller.dto.PublicBookingResponse;
import com.creditcard.booking.service.OnlineBookingService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Guest-facing routes. The gateway exposes exactly these two paths without a JWT, and
 * rate-limits them by client IP. Nothing else under /api/v1/public is routed.
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicBookingController {

    private final OnlineBookingService onlineBookingService;

    public PublicBookingController(OnlineBookingService onlineBookingService) {
        this.onlineBookingService = onlineBookingService;
    }

    @GetMapping("/availability")
    public PublicAvailabilityResponse availability(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut) {
        return onlineBookingService.search(checkIn, checkOut, LocalDate.now());
    }

    @PostMapping("/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public PublicBookingResponse book(@Valid @RequestBody PublicBookingRequest request) {
        return onlineBookingService.book(request, LocalDate.now());
    }
}
