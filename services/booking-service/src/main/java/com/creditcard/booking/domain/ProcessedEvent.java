package com.creditcard.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_key", length = 255)
    private String eventKey;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    public ProcessedEvent() {}

    public ProcessedEvent(String eventKey) {
        this.eventKey = eventKey;
        this.processedAt = Instant.now();
    }

    public String getEventKey() { return eventKey; }

    public Instant getProcessedAt() { return processedAt; }
}
