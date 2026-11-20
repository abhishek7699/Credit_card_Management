package com.creditcard.booking.service;

/** What a booking keeps of a card check: the verdict, Mastercard's referenceId and the last 4 digits. Nothing else. */
public record CardValidationResult(String status, String referenceId, String last4) {
}
