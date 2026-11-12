package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.Transaction;
import com.creditcard.transaction.domain.TransactionStatus;
import com.creditcard.transaction.event.StatusUpdateEvent;
import com.creditcard.transaction.event.TransactionInitiatedEvent;
import com.creditcard.transaction.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);
    private static final String TOPIC_TRANSACTION_INITIATED = "transaction.initiated";
    public static final String TOPIC_STATUS_UPDATED = "transaction.status.updated";
    private static final String SOURCE_SERVICE = "transaction-service";

    private final TokenizationService tokenizationService;
    private final TransactionRepository transactionRepository;
    private final KafkaTemplate<String, TransactionInitiatedEvent> kafkaTemplate;
    private final OutboxService outboxService;

    public TransactionService(
            TokenizationService tokenizationService,
            TransactionRepository transactionRepository,
            KafkaTemplate<String, TransactionInitiatedEvent> kafkaTemplate,
            OutboxService outboxService) {
        this.tokenizationService = tokenizationService;
        this.transactionRepository = transactionRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.outboxService = outboxService;
    }

    @Transactional
    public UUID initiateTransaction(String rawCardNumber, String cardholderName, BigDecimal amount, String currencyCode, String merchantId) {
        // Tokenize raw PAN via Vault — raw PAN reference is dropped after this call
        TokenResult tokenResult = tokenizationService.tokenize(rawCardNumber);

        Transaction tx = new Transaction();
        tx.setCardToken(tokenResult.getToken());
        tx.setMaskedCardNumber(tokenResult.getMaskedCardNumber());
        tx.setCardholderName(cardholderName);
        tx.setAmount(amount);
        tx.setCurrencyCode(currencyCode);
        tx.setMerchantId(merchantId.isBlank() ? null : merchantId);
        tx.setStatus(TransactionStatus.PENDING);

        Transaction saved = transactionRepository.save(tx);

        TransactionInitiatedEvent event = buildInitiatedEvent(saved);
        kafkaTemplate.send(TOPIC_TRANSACTION_INITIATED, saved.getId().toString(), event);

        return saved.getId();
    }

    /**
     * Applies authorization's raw decision (013 option A). The state change is a conditional
     * update from PENDING, so a late result never overwrites a terminal state (including one
     * set by the timeout sweep). Only the update that wins writes the final outcome to the
     * outbox, in the same DB transaction, so it is published exactly once.
     */
    @Transactional
    public void applyAuthorizationResult(String transactionId, String outcome, String reasonCode) {
        UUID id = UUID.fromString(transactionId);
        TransactionStatus target = finalStatusFor(outcome);
        if (target == null) {
            log.warn("Unknown authorization outcome '{}' for transaction {}; ignored", outcome, id);
            return;
        }
        Optional<Transaction> current = transactionRepository.findById(id);
        if (current.isEmpty()) {
            log.warn("Authorization result for unknown transaction {}; ignored", id);
            return;
        }
        Transaction tx = current.get();
        String eventType = eventTypeFor(target);
        StatusUpdateEvent event = finalEvent(eventType, tx, target, reasonCode);

        int updated = transactionRepository.transitionStatus(id, TransactionStatus.PENDING, target, Instant.now());
        if (updated == 1) {
            outboxService.append(TOPIC_STATUS_UPDATED, stableEventId(id, eventType), id.toString(), event);
        }
    }

    /** Stable across relay replays: derived from the transaction and the final event type. */
    public static UUID stableEventId(UUID transactionId, String eventType) {
        return UUID.nameUUIDFromBytes((transactionId + ":" + eventType).getBytes(StandardCharsets.UTF_8));
    }

    static TransactionStatus finalStatusFor(String outcome) {
        if (outcome == null) {
            return null;
        }
        return switch (outcome) {
            case "AUTHORIZED" -> TransactionStatus.AUTHORIZED;
            case "DECLINED" -> TransactionStatus.DECLINED;
            case "FAILED" -> TransactionStatus.FAILED;
            default -> null;
        };
    }

    static String eventTypeFor(TransactionStatus status) {
        return switch (status) {
            case AUTHORIZED -> "TransactionAuthorized";
            case DECLINED -> "TransactionDeclined";
            case FAILED -> "TransactionFailed";
            default -> throw new IllegalArgumentException("not a final status: " + status);
        };
    }

    private StatusUpdateEvent finalEvent(String eventType, Transaction tx, TransactionStatus target, String reasonCode) {
        StatusUpdateEvent.Payload payload = new StatusUpdateEvent.Payload();
        payload.setTransactionId(tx.getId().toString());
        payload.setCardToken(tx.getCardToken());
        payload.setOutcome(target.name());
        payload.setReasonCode(reasonCode);

        StatusUpdateEvent event = new StatusUpdateEvent();
        event.setEventId(stableEventId(tx.getId(), eventType).toString());
        event.setEventType(eventType);
        event.setSourceService(SOURCE_SERVICE);
        event.setTraceId(MDC.get("traceId"));
        event.setOccurredAt(Instant.now());
        event.setPayload(payload);
        return event;
    }

    @Transactional(readOnly = true)
    public Optional<Transaction> getTransaction(String transactionId) {
        UUID id = UUID.fromString(transactionId);
        return transactionRepository.findById(id);
    }

    private TransactionInitiatedEvent buildInitiatedEvent(Transaction tx) {
        TransactionInitiatedEvent.Payload payload = new TransactionInitiatedEvent.Payload();
        payload.setTransactionId(tx.getId().toString());
        payload.setCardToken(tx.getCardToken());
        payload.setMaskedCardNumber(tx.getMaskedCardNumber());
        payload.setCardholderName(tx.getCardholderName());
        payload.setAmount(tx.getAmount());
        payload.setCurrencyCode(tx.getCurrencyCode());
        payload.setMerchantId(tx.getMerchantId());

        return new TransactionInitiatedEvent(null, payload);
    }
}
