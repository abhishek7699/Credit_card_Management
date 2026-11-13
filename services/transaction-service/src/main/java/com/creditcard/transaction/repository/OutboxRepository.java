package com.creditcard.transaction.repository;

import com.creditcard.transaction.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Rows the relay still has to send: not sent yet, and not given up on. */
    List<OutboxEvent> findTop100BySentAtIsNullAndFailedAtIsNullOrderByCreatedAtAsc();

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.sentAt = :sentAt WHERE e.eventId = :eventId AND e.sentAt IS NULL")
    int markSent(@Param("eventId") UUID eventId, @Param("sentAt") Instant sentAt);

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.attempts = :attempts, e.lastError = :error "
            + "WHERE e.eventId = :eventId AND e.sentAt IS NULL AND e.failedAt IS NULL")
    int recordAttempt(@Param("eventId") UUID eventId,
                      @Param("attempts") int attempts,
                      @Param("error") String error);

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.attempts = :attempts, e.lastError = :error, e.failedAt = :failedAt "
            + "WHERE e.eventId = :eventId AND e.sentAt IS NULL AND e.failedAt IS NULL")
    int markFailed(@Param("eventId") UUID eventId,
                   @Param("attempts") int attempts,
                   @Param("error") String error,
                   @Param("failedAt") Instant failedAt);
}
