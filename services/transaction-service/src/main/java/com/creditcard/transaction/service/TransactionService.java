package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.Transaction;
import com.creditcard.transaction.domain.TransactionStatus;
import com.creditcard.transaction.event.TransactionInitiatedEvent;
import com.creditcard.transaction.repository.TransactionRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransactionService {

    private static final String TOPIC_TRANSACTION_INITIATED = "transaction.initiated";

    private final TokenizationService tokenizationService;
    private final TransactionRepository transactionRepository;
    private final KafkaTemplate<String, TransactionInitiatedEvent> kafkaTemplate;

    public TransactionService(
            TokenizationService tokenizationService,
            TransactionRepository transactionRepository,
            KafkaTemplate<String, TransactionInitiatedEvent> kafkaTemplate) {
        this.tokenizationService = tokenizationService;
        this.transactionRepository = transactionRepository;
        this.kafkaTemplate = kafkaTemplate;
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

    @Transactional
    public void handleAuthorized(String transactionId) {
        UUID id = UUID.fromString(transactionId);
        transactionRepository.findById(id).ifPresent(tx -> {
            if (!tx.getStatus().isTerminal()) {
                transactionRepository.updateStatusById(id, TransactionStatus.AUTHORIZED);
            }
        });
    }

    @Transactional
    public void handleDeclined(String transactionId) {
        UUID id = UUID.fromString(transactionId);
        transactionRepository.findById(id).ifPresent(tx -> {
            if (!tx.getStatus().isTerminal()) {
                transactionRepository.updateStatusById(id, TransactionStatus.DECLINED);
            }
        });
    }

    @Transactional(readOnly = true)
    public Optional<Transaction> getTransaction(String transactionId) {
        UUID id = UUID.fromString(transactionId);
        return transactionRepository.findById(id);
    }

    @Transactional
    public void handleFailed(String transactionId) {
        UUID id = UUID.fromString(transactionId);
        transactionRepository.findById(id).ifPresent(tx -> {
            if (!tx.getStatus().isTerminal()) {
                transactionRepository.updateStatusById(id, TransactionStatus.FAILED);
            }
        });
    }

    private TransactionInitiatedEvent buildInitiatedEvent(Transaction tx) {
        TransactionInitiatedEvent.Payload payload = new TransactionInitiatedEvent.Payload();
        payload.setTransactionId(tx.getId().toString());
        payload.setCardToken(tx.getCardToken());
        payload.setMaskedCardNumber(tx.getMaskedCardNumber());
        payload.setCardholderName(tx.getCardholderName());
        payload.setAmount(tx.getAmount());
        payload.setCurrencyCode(tx.getCurrencyCode());

        return new TransactionInitiatedEvent(null, payload);
    }
}
