package com.creditcard.booking.exception;

import java.math.BigDecimal;
import java.util.UUID;

/** Checkout was attempted with a balance due and no card was supplied (contracts/api.md
 *  — a 400, unlike the 409s for a wrong booking state). */
public class CardRequiredException extends RuntimeException {
    public CardRequiredException(UUID bookingId, BigDecimal balanceDue) {
        super("Booking " + bookingId + " has a balance due of " + balanceDue + " — a card is required to check out");
    }
}
