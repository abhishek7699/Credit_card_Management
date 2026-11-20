package com.creditcard.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A transaction outcome whose transactionId has no checkout attempt linked to it yet. The
 * attempt is linked only after its InitiateTransaction reply, so an outcome can arrive first.
 * The row is kept until it is applied; it is never deleted while unmatched.
 */
@Entity
@Table(name = "unmatched_payment_outcomes")
public class UnmatchedPaymentOutcome {

    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private String transactionId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "outcome", nullable = false, updatable = false)
    private String outcome;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected UnmatchedPaymentOutcome() {
    }

    public String getTransactionId() { return transactionId; }

    public String getEventId() { return eventId; }

    public String getOutcome() { return outcome; }

    public Instant getReceivedAt() { return receivedAt; }
}
