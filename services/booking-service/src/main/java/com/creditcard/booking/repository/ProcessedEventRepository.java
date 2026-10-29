package com.creditcard.booking.repository;

import com.creditcard.booking.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    /** Same idempotent-insert pattern as every other service's processed_events table
     *  (Principle III) — returns 0 if the key was already present (duplicate delivery). */
    @Modifying
    @Query(value = "INSERT INTO processed_events (event_key, processed_at) VALUES (:key, NOW()) ON CONFLICT DO NOTHING",
           nativeQuery = true)
    int insertIfAbsent(@Param("key") String key);
}
