package com.creditcard.notification.repository;

import com.creditcard.notification.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    @Modifying
    @Query(value = "INSERT INTO processed_events (event_key, processed_at) VALUES (:key, NOW()) ON CONFLICT DO NOTHING",
           nativeQuery = true)
    int insertIfAbsent(@Param("key") String key);
}
