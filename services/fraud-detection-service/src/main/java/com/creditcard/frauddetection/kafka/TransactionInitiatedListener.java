package com.creditcard.frauddetection.kafka;

import com.creditcard.frauddetection.kafka.event.FraudAlertRaisedEvent;
import com.creditcard.frauddetection.kafka.event.TransactionInitiatedEvent;
import com.creditcard.frauddetection.service.FraudDetectionService;
import com.creditcard.frauddetection.service.IdempotencyService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class TransactionInitiatedListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionInitiatedListener.class);
    private static final String OUTPUT_TOPIC = "fraud.alert.raised";
    private static final String EVENT_TYPE = "TransactionInitiated";

    private final FraudDetectionService fraudDetectionService;
    private final IdempotencyService idempotencyService;
    private final KafkaTemplate<String, FraudAlertRaisedEvent> kafkaTemplate;

    public TransactionInitiatedListener(FraudDetectionService fraudDetectionService,
                                         IdempotencyService idempotencyService,
                                         KafkaTemplate<String, FraudAlertRaisedEvent> kafkaTemplate) {
        this.fraudDetectionService = fraudDetectionService;
        this.idempotencyService = idempotencyService;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = "transaction.initiated", groupId = "${spring.kafka.consumer.group-id}")
    public void onMessage(ConsumerRecord<String, TransactionInitiatedEvent> record, Acknowledgment ack) {
        try {
            TransactionInitiatedEvent event = record.value();

            if (event == null) {
                log.warn("Received null payload on transaction.initiated, discarding offset={}", record.offset());
                return;
            }

            if (event.getPayload() == null || event.getPayload().getTransactionId() == null) {
                log.warn("Received event with null or invalid payload eventId={}, discarding", event.getEventId());
                return;
            }

            String key = idempotencyService.buildKey(EVENT_TYPE, event.getEventId());
            if (idempotencyService.isDuplicate(key)) {
                log.debug("Duplicate event detected key={}, skipping", key);
                return;
            }

            Optional<FraudAlertRaisedEvent> alertEvent = fraudDetectionService.evaluate(event);
            alertEvent.ifPresent(e -> kafkaTemplate.send(OUTPUT_TOPIC, e.getTransactionId().toString(), e));

        } catch (Exception e) {
            log.warn("Error processing transaction.initiated offset={}: {}", record.offset(), e.getMessage());
        } finally {
            ack.acknowledge();
        }
    }
}
