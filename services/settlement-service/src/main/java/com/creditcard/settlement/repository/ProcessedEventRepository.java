package com.creditcard.settlement.repository;

import com.creditcard.settlement.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO processed_events (event_key, processed_at) VALUES (:key, NOW()) ON CONFLICT DO NOTHING",
           nativeQuery = true)
    int insertIfAbsent(@Param("key") String key);
}
