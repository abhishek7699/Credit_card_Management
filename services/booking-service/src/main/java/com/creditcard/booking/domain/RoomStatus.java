package com.creditcard.booking.domain;

/**
 * OCCUPIED is reached only via check-in and left only via check-out (to DIRTY) — the
 * PATCH /status endpoint never accepts OCCUPIED as a target and never accepts a
 * transition out of it either. Its own allowed transitions are exactly:
 * DIRTY -> VACANT_CLEAN, VACANT_CLEAN/DIRTY -> MAINTENANCE, MAINTENANCE -> VACANT_CLEAN
 * (FR-010, data-model.md).
 */
public enum RoomStatus {
    VACANT_CLEAN,
    OCCUPIED,
    DIRTY,
    MAINTENANCE
}
