package com.creditcard.authorization.kafka;

import com.creditcard.authorization.domain.ReasonCode;
import com.creditcard.authorization.kafka.event.AuthorizationResultEvent;
import com.creditcard.authorization.kafka.event.TransactionAuthorizedEvent;
import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import com.creditcard.authorization.service.AuthorizationService;
import com.creditcard.authorization.service.IdempotencyService;
import com.creditcard.authorization.tracing.CurrentTraceId;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class TransactionInitiatedListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionInitiatedListener.class);
    private static final String RESULT_TOPIC = "transaction.status.updated";
    private static final String AUTHORIZED_TOPIC = "transaction.authorized";
    private static final String EVENT_TYPE_AUTHORIZED = "TransactionAuthorized";

    private final AuthorizationService authorizationService;
    private final IdempotencyService idempotencyService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final CurrentTraceId currentTraceId;

    public TransactionInitiatedListener(
            AuthorizationService authorizationService,
            IdempotencyService idempotencyService,
            KafkaTemplate<String, Object> kafkaTemplate,
            CurrentTraceId currentTraceId) {
        this.authorizationService = authorizationService;
        this.idempotencyService = idempotencyService;
        this.kafkaTemplate = kafkaTemplate;
        this.currentTraceId = currentTraceId;
    }

    @KafkaListener(
        topics = "${authorization.kafka.consumer.topic:transaction.initiated}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void onMessage(ConsumerRecord<String, TransactionInitiatedEvent> record, Acknowledgment ack) {
        TransactionInitiatedEvent event = record.value();
        String traceId = resolveTraceId(event);

        if (event == null || event.getPayload() == null || event.getPayload().getTransactionId() == null) {
            log.warn("Received null or invalid event payload on partition={} offset={}",
                    record.partition(), record.offset());
            String key = record.key() != null ? record.key() : UUID.randomUUID().toString();
            UUID txId = parseTransactionId(key);
            AuthorizationResultEvent invalid = AuthorizationResultEvent.failed(txId, "", ReasonCode.INVALID_EVENT_PAYLOAD);
            invalid.setTraceId(traceId);
            kafkaTemplate.send(RESULT_TOPIC, key, invalid);
            ack.acknowledge();
            return;
        }

        TransactionInitiatedEvent.Payload payload = event.getPayload();
        String txKey = payload.getTransactionId().toString();
        try {
            String idempotencyKey = idempotencyService.buildKey(event.getEventType(), event.getEventId());

            if (idempotencyService.isDuplicate(idempotencyKey)) {
                log.info("Skipping duplicate event eventId={} txId={}", event.getEventId(), payload.getTransactionId());
                return;
            }

            AuthorizationResultEvent result = authorizationService.authorize(event);
            result.setTraceId(traceId);
            kafkaTemplate.send(RESULT_TOPIC, txKey, result);

            if (EVENT_TYPE_AUTHORIZED.equals(result.getEventType())) {
                TransactionAuthorizedEvent authorized = TransactionAuthorizedEvent.from(payload, Instant.now());
                authorized.setTraceId(traceId);
                kafkaTemplate.send(AUTHORIZED_TOPIC, txKey, authorized);
            }

            log.info("Authorization decision={} txId={}", result.getEventType(), payload.getTransactionId());
        } catch (Exception ex) {
            log.error("Authorization processing failed txId={}: {}", payload.getTransactionId(), ex.getMessage());
            AuthorizationResultEvent failed = AuthorizationResultEvent.failed(payload.getTransactionId(),
                    payload.getCardToken(), ReasonCode.INTERNAL_ERROR);
            failed.setTraceId(traceId);
            kafkaTemplate.send(RESULT_TOPIC, txKey, failed);
        } finally {
            ack.acknowledge();
        }
    }

    /** The live span's trace (the consumer span continues the producer's trace), else the envelope's own traceId. */
    private String resolveTraceId(TransactionInitiatedEvent event) {
        String current = currentTraceId.get();
        if (current != null) {
            return current;
        }
        return event == null ? null : event.getTraceId();
    }

    private UUID parseTransactionId(String key) {
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            return UUID.randomUUID();
        }
    }
}
