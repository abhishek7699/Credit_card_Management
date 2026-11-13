package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.OutboxEvent;
import com.creditcard.transaction.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends one outbox row and marks it sent. The stored JSON is sent unchanged, so each topic keeps
 * its own envelope shape. The mark and the send are not atomic: a crash between them sends the
 * row again, which is safe because consumers drop a repeated eventId via processed_events.
 *
 * <p>Each call runs in its own transaction ({@code REQUIRES_NEW}), so it can run right after the
 * owning transaction commits (see {@link OutboxService}) as well as from the polling relay. Callers
 * must reach this bean through its proxy (never by calling a method on {@code this}), or the
 * transaction is skipped.
 */
@Component
public class OutboxPublisher {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_ERROR_LENGTH = 512;

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public OutboxPublisher(OutboxRepository outboxRepository,
                           ObjectMapper objectMapper,
                           @Qualifier("outboxKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
    }

    /** Publishes one row and marks it sent. Throws on failure; the row stays unsent. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(OutboxEvent row) {
        JsonNode body = readBody(row);
        send(row, body);
        outboxRepository.markSent(row.getEventId(), Instant.now());
    }

    /**
     * Records one failed attempt for a row. Returns true when the row reached the attempt limit and
     * is now marked failed, so the relay stops retrying it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(OutboxEvent row, String error, int maxAttempts) {
        int attempts = row.getAttempts() + 1;
        String truncated = error == null ? null
                : error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        if (attempts >= maxAttempts) {
            outboxRepository.markFailed(row.getEventId(), attempts, truncated, Instant.now());
            return true;
        }
        outboxRepository.recordAttempt(row.getEventId(), attempts, truncated);
        return false;
    }

    private JsonNode readBody(OutboxEvent row) {
        try {
            return objectMapper.readTree(row.getPayload());
        } catch (JsonProcessingException e) {
            throw new OutboxRowException(row, "stored payload is not valid JSON", e);
        }
    }

    private void send(OutboxEvent row, JsonNode body) {
        try {
            kafkaTemplate.send(row.getTopic(), row.getMessageKey(), body)
                    .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted publishing outbox row " + row.getEventId(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RecordTooLargeException || cause instanceof SerializationException) {
                throw new OutboxRowException(row, "broker rejected the record: " + cause.getMessage(), cause);
            }
            throw new IllegalStateException("publish failed for outbox row " + row.getEventId(), e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("publish timed out for outbox row " + row.getEventId(), e);
        }
    }
}
