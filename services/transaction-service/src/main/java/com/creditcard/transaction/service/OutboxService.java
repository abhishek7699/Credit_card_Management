package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.OutboxEvent;
import com.creditcard.transaction.event.StatusUpdateEvent;
import com.creditcard.transaction.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes outbox rows (013). {@code MANDATORY} propagation: it refuses to run outside a DB
 * transaction, so the event row can only be committed together with the state change it
 * describes. A crash after commit leaves the row for OutboxRelay to publish.
 */
@Service
public class OutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, UUID eventId, String messageKey, StatusUpdateEvent event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize outbox event " + eventId, e);
        }
        outboxRepository.save(new OutboxEvent(eventId, topic, messageKey, event.getEventType(), json));
    }
}
