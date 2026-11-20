package com.creditcard.booking.domain;

/**
 * Who created a booking. STAFF: front-desk staff on a guest's behalf (the original
 * flow). ONLINE: the guest themselves through the public front office, which is
 * paid at checkout like every other booking.
 */
public enum BookingSource {
    STAFF,
    ONLINE
}
