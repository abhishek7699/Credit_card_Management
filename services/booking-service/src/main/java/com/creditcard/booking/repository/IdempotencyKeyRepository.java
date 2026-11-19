package com.creditcard.booking.repository;

import com.creditcard.booking.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

    /** Clears an expired row for this key so it can be claimed again. */
    @Modifying
    @Query(value = "DELETE FROM idempotency_keys WHERE idempotency_key = :key AND expires_at <= :now",
           nativeQuery = true)
    int deleteIfExpired(@Param("key") String key, @Param("now") Instant now);

    /** Claims the key. Returns 0 if a row already exists (ON CONFLICT DO NOTHING), which
     *  blocks until any in-flight claimant commits or rolls back. */
    @Modifying
    @Query(value = "INSERT INTO idempotency_keys (idempotency_key, created_at, expires_at) "
                 + "VALUES (:key, :now, :expiresAt) ON CONFLICT DO NOTHING",
           nativeQuery = true)
    int insertIfAbsent(@Param("key") String key, @Param("now") Instant now, @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Query(value = "UPDATE idempotency_keys SET resource_id = :resourceId, response_status = :status "
                 + "WHERE idempotency_key = :key",
           nativeQuery = true)
    int recordResult(@Param("key") String key, @Param("resourceId") UUID resourceId, @Param("status") String status);

    /** Scheduled purge: keeps the table from growing without bound. */
    @Modifying
    @Query(value = "DELETE FROM idempotency_keys WHERE expires_at <= :now", nativeQuery = true)
    int purgeExpired(@Param("now") Instant now);
}
