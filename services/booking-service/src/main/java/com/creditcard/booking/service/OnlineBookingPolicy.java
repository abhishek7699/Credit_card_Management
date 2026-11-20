package com.creditcard.booking.service;

import com.creditcard.booking.exception.BookingWindowException;
import com.creditcard.booking.exception.InvalidDateRangeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The limits that apply only to guest-made bookings (public front office). Staff
 * bookings are not limited by these. Both limits are configurable under
 * {@code booking.online.*}.
 */
@Component
public class OnlineBookingPolicy {

    private final int maxStayNights;
    private final int maxDaysAhead;

    public OnlineBookingPolicy(@Value("${booking.online.max-stay-nights:14}") int maxStayNights,
                               @Value("${booking.online.max-days-ahead:365}") int maxDaysAhead) {
        this.maxStayNights = maxStayNights;
        this.maxDaysAhead = maxDaysAhead;
    }

    /**
     * Rejects a date range a guest may not book: checkOut not after checkIn, a
     * check-in before {@code today}, a check-in more than {@code maxDaysAhead} days
     * after {@code today}, or a stay longer than {@code maxStayNights}.
     */
    public void validate(LocalDate checkIn, LocalDate checkOut, LocalDate today) {
        if (!checkOut.isAfter(checkIn)) {
            throw new InvalidDateRangeException(checkIn, checkOut);
        }
        if (checkIn.isBefore(today)) {
            throw new BookingWindowException("check-in (" + checkIn + ") cannot be before today (" + today + ")");
        }
        if (checkIn.isAfter(today.plusDays(maxDaysAhead))) {
            throw new BookingWindowException("bookings can be made at most " + maxDaysAhead + " days ahead");
        }
        long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
        if (nights > maxStayNights) {
            throw new BookingWindowException("a stay can be at most " + maxStayNights + " nights (requested " + nights + ")");
        }
    }

    public int getMaxStayNights() { return maxStayNights; }

    public int getMaxDaysAhead() { return maxDaysAhead; }
}
