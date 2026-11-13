package com.creditcard.transaction.api.kafka;

import com.creditcard.transaction.event.StatusUpdateEvent;
import com.creditcard.transaction.service.IdempotencyService;
import com.creditcard.transaction.service.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes authorization's raw decision on {@code transaction.authorization.result} (013,
 * option A). transaction-service is the only publisher of final outcomes, so this listener
 * no longer reads {@code transaction.status.updated}; that would loop its own output back.
 */
@Component
public class AuthorizationResultListener {

    public static final String TOPIC = "transaction.authorization.result";

    private static final Logger log = LoggerFactory.getLogger(AuthorizationResultListener.class);

    private final TransactionService transactionService;
    private final IdempotencyService idempotencyService;

    public AuthorizationResultListener(TransactionService transactionService, IdempotencyService idempotencyService) {
        this.transactionService = transactionService;
        this.idempotencyService = idempotencyService;
    }

    @KafkaListener(topics = TOPIC, containerFactory = "kafkaListenerContainerFactory")
    public void onAuthorizationResult(StatusUpdateEvent event, Acknowledgment ack) {
        String eventKey = "tx:" + event.getEventType() + ":" + event.getEventId();
        idempotencyService.recordAndExecute(eventKey, () -> route(event));
        ack.acknowledge();
    }

    private void route(StatusUpdateEvent event) {
        StatusUpdateEvent.Payload payload = event.getPayload();
        log.debug("Authorization result outcome={} for transaction {}", payload.getOutcome(), payload.getTransactionId());
        transactionService.applyAuthorizationResult(
                payload.getTransactionId(), payload.getOutcome(), payload.getReasonCode());
    }
}
