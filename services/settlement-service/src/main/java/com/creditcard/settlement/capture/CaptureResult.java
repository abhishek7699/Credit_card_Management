package com.creditcard.settlement.capture;

/**
 * What a capture attempt means for settlement (spec 014 phase 4).
 * Only CAPTURED lets a transaction be marked SETTLED. FAILED and UNKNOWN leave it AUTHORIZED for the next run.
 */
public enum CaptureResult {
    CAPTURED,
    FAILED,
    UNKNOWN
}
