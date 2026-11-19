package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.OutboxEvent;
import com.creditcard.transaction.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
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
    private final Tracer tracer;

    public OutboxPublisher(OutboxRepository outboxRepository,
                           ObjectMapper objectMapper,
                           @Qualifier("outboxKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
                           Tracer tracer) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.tracer = tracer;
    }

    /** Publishes one row and marks it sent. Throws on failure; the row stays unsent. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(OutboxEvent row) {
        JsonNode body = readBody(row);
        Span span = startRelaySpan(row);
        try (Tracer.SpanInScope ignored = span == null ? null : tracer.withSpan(span)) {
            send(row, body);
        } finally {
            if (span != null) {
                span.end();
            }
        }
        outboxRepository.markSent(row.getEventId(), Instant.now());
    }

    /**
     * A span inside the trace the row was written under, so the Kafka record this relay sends carries
     * that trace. Null for rows written before tracing: the producer then starts its own trace.
     */
    private Span startRelaySpan(OutboxEvent row) {
        if (row.getTraceId() == null) {
            return null;
        }
        TraceContext parent = tracer.traceContextBuilder()
                .traceId(row.getTraceId())
                .spanId(row.getSpanId())
                .sampled(row.getTraceSampled())
                .build();
        return tracer.spanBuilder().setParent(parent).name("outbox publish").start();
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
