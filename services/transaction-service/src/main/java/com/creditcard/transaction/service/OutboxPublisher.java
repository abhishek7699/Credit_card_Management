package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.OutboxEvent;
import com.creditcard.transaction.event.StatusUpdateEvent;
import com.creditcard.transaction.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Publishes one outbox row and marks it sent. The mark and the send are not atomic: if the
 * process dies between them, the row is sent again on the next relay pass. That is safe
 * because consumers drop a repeated eventId via processed_events.
 */
@Component
public class OutboxPublisher {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, StatusUpdateEvent> kafkaTemplate;

    public OutboxPublisher(OutboxRepository outboxRepository,
                           ObjectMapper objectMapper,
                           @Qualifier("statusKafkaTemplate") KafkaTemplate<String, StatusUpdateEvent> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional
    public void publish(OutboxEvent row) {
        StatusUpdateEvent event;
        try {
            event = objectMapper.readValue(row.getPayload(), StatusUpdateEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("outbox row " + row.getEventId() + " is not valid JSON", e);
        }
        try {
            kafkaTemplate.send(row.getTopic(), row.getMessageKey(), event)
                    .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted publishing outbox row " + row.getEventId(), e);
        } catch (Exception e) {
            throw new IllegalStateException("publish failed for outbox row " + row.getEventId(), e);
        }
        outboxRepository.markSent(row.getEventId(), Instant.now());
    }
}
