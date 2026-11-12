package com.creditcard.authorization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 013: a transaction timed out before its authorization ran. A late {@code transaction.initiated} for
 * the same transaction finds this row and skips authorization, so no credit is held for a transaction
 * that is already FAILED.
 */
@Entity
@Table(name = "timeout_markers")
public class TimeoutMarker {

    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "marked_at", nullable = false, updatable = false)
    private Instant markedAt;

    public TimeoutMarker() {}

    public TimeoutMarker(UUID transactionId) {
        this.transactionId = transactionId;
    }

    @PrePersist
    void onCreate() {
        markedAt = Instant.now();
    }

    public UUID getTransactionId() { return transactionId; }
    public Instant getMarkedAt() { return markedAt; }
}
