package com.creditcard.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A client-supplied Idempotency-Key and the result of the first successful request
 *  that used it. Rows are inserted and updated through IdempotencyKeyRepository's native
 *  queries; this entity exists so reads map straight onto the table. */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "response_status", length = 20)
    private String responseStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    public IdempotencyKey() {}

    public UUID getResourceId() { return resourceId; }

    public String getResponseStatus() { return responseStatus; }
}
