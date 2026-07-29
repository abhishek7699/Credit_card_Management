package com.creditcard.transaction.api.kafka;

import com.creditcard.transaction.event.StatusUpdateEvent;
import com.creditcard.transaction.service.IdempotencyService;
import com.creditcard.transaction.service.TransactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class TransactionStatusListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionStatusListener.class);

    private final TransactionService transactionService;
    private final IdempotencyService idempotencyService;

    public TransactionStatusListener(TransactionService transactionService, IdempotencyService idempotencyService) {
        this.transactionService = transactionService;
        this.idempotencyService = idempotencyService;
    }

    @KafkaListener(topics = "transaction.status.updated", containerFactory = "kafkaListenerContainerFactory")
    public void onStatusUpdate(StatusUpdateEvent event, Acknowledgment ack) {
        String eventKey = "tx:" + event.getEventType() + ":" + event.getEventId();
        idempotencyService.recordAndExecute(eventKey, () -> route(event));
        ack.acknowledge();
    }

    private void route(StatusUpdateEvent event) {
        String txId = event.getPayload().getTransactionId();
        switch (event.getEventType()) {
            case "TransactionAuthorized" -> transactionService.handleAuthorized(txId);
            case "TransactionDeclined"   -> transactionService.handleDeclined(txId);
            case "TransactionFailed"     -> transactionService.handleFailed(txId);
            default -> log.warn("Unhandled event type: {}", event.getEventType());
        }
    }
}
