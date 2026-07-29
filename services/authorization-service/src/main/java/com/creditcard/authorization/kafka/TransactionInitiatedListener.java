package com.creditcard.authorization.kafka;

import com.creditcard.authorization.domain.ReasonCode;
import com.creditcard.authorization.kafka.event.AuthorizationResultEvent;
import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import com.creditcard.authorization.service.AuthorizationService;
import com.creditcard.authorization.service.IdempotencyService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TransactionInitiatedListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionInitiatedListener.class);
    private static final String RESULT_TOPIC = "transaction.status.updated";

    private final AuthorizationService authorizationService;
    private final IdempotencyService idempotencyService;
    private final KafkaTemplate<String, AuthorizationResultEvent> kafkaTemplate;

    public TransactionInitiatedListener(
            AuthorizationService authorizationService,
            IdempotencyService idempotencyService,
            KafkaTemplate<String, AuthorizationResultEvent> kafkaTemplate) {
        this.authorizationService = authorizationService;
        this.idempotencyService = idempotencyService;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(
        topics = "${authorization.kafka.consumer.topic:transaction.initiated}",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void onMessage(ConsumerRecord<String, TransactionInitiatedEvent> record, Acknowledgment ack) {
        TransactionInitiatedEvent event = record.value();

        if (event == null || event.getTransactionId() == null) {
            log.warn("Received null or invalid event payload on partition={} offset={}",
                    record.partition(), record.offset());
            String key = record.key() != null ? record.key() : UUID.randomUUID().toString();
            UUID txId = parseTransactionId(key);
            AuthorizationResultEvent failed = AuthorizationResultEvent.failed(txId, "", ReasonCode.INVALID_EVENT_PAYLOAD);
            kafkaTemplate.send(RESULT_TOPIC, key, failed);
            ack.acknowledge();
            return;
        }

        String txKey = event.getTransactionId().toString();
        try {
            String idempotencyKey = idempotencyService.buildKey(event.getEventType(), event.getEventId());

            if (idempotencyService.isDuplicate(idempotencyKey)) {
                log.info("Skipping duplicate event eventId={} txId={}", event.getEventId(), event.getTransactionId());
                return;
            }

            AuthorizationResultEvent result = authorizationService.authorize(event);
            kafkaTemplate.send(RESULT_TOPIC, txKey, result);
            log.info("Authorization decision={} txId={}", result.getEventType(), event.getTransactionId());
        } catch (Exception ex) {
            log.error("Authorization processing failed txId={}: {}", event.getTransactionId(), ex.getMessage());
            AuthorizationResultEvent failed = AuthorizationResultEvent.failed(
                    event.getTransactionId(), event.getCardToken(), ReasonCode.INTERNAL_ERROR);
            kafkaTemplate.send(RESULT_TOPIC, txKey, failed);
        } finally {
            ack.acknowledge();
        }
    }

    private UUID parseTransactionId(String key) {
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            return UUID.randomUUID();
        }
    }
}
