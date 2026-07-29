package com.creditcard.frauddetection.repository;

import com.creditcard.frauddetection.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    @Modifying
    @Transactional
    @Query(
        value = "INSERT INTO processed_events (event_key, processed_at) VALUES (:eventKey, now()) ON CONFLICT DO NOTHING",
        nativeQuery = true
    )
    int insertIfAbsent(@Param("eventKey") String eventKey);

    boolean existsByEventKey(String eventKey);
}
