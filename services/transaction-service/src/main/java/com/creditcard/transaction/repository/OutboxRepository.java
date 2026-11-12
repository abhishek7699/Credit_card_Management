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

    List<OutboxEvent> findTop100BySentAtIsNullOrderByCreatedAtAsc();

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.sentAt = :sentAt WHERE e.eventId = :eventId AND e.sentAt IS NULL")
    int markSent(@Param("eventId") UUID eventId, @Param("sentAt") Instant sentAt);
}
