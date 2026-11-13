package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.OutboxEvent;
import com.creditcard.transaction.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Writes outbox rows (013). {@code MANDATORY} propagation: it refuses to run outside a DB
 * transaction, so a row can only be committed together with the state change it describes.
 *
 * <p>After the transaction commits, the row is published right away. The polling relay is the
 * fallback: if the immediate publish fails, or the process dies first, the row stays unsent and the
 * relay sends it.
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final OutboxPublisher outboxPublisher;

    public OutboxService(OutboxRepository outboxRepository,
                         ObjectMapper objectMapper,
                         OutboxPublisher outboxPublisher) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.outboxPublisher = outboxPublisher;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, UUID eventId, String messageKey, String eventType, Object event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize outbox event " + eventId, e);
        }
        OutboxEvent row = new OutboxEvent(eventId, topic, messageKey, eventType, json);
        outboxRepository.save(row);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // A failure here must not fail the caller: the state change is already committed,
                // and the row stays unsent for the relay.
                try {
                    outboxPublisher.publish(row);
                } catch (RuntimeException e) {
                    log.warn("Immediate publish failed for outbox row {} (transactionId {}); relay will retry: {}",
                            row.getEventId(), row.getMessageKey(), e.getMessage());
                }
            }
        });
    }
}
