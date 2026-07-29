package com.creditcard.transaction.repository;

import com.creditcard.transaction.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    boolean existsByEventKey(String eventKey);

    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO processed_events (event_key, processed_at) VALUES (:eventKey, now()) ON CONFLICT DO NOTHING",
           nativeQuery = true)
    int insertIfAbsent(@Param("eventKey") String eventKey);
}
