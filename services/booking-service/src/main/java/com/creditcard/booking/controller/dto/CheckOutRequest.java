package com.creditcard.booking.controller.dto;

import jakarta.validation.Valid;

/** Optional entirely (no body, or {@code {}}, is valid for a zero-balance checkout);
 *  {@code card} is required only when the booking's balance due is greater than
 *  zero — enforced in the service layer, not here (contracts/api.md). */
public record CheckOutRequest(@Valid CardDetails card) {
}
