package com.creditcard.frauddetection.service;

import com.creditcard.frauddetection.domain.FraudAlert;
import com.creditcard.frauddetection.domain.TransactionRecord;
import com.creditcard.frauddetection.kafka.event.FraudAlertRaisedEvent;
import com.creditcard.frauddetection.kafka.event.TransactionInitiatedEvent;
import com.creditcard.frauddetection.repository.FraudAlertRepository;
import com.creditcard.frauddetection.repository.TransactionRecordRepository;
import com.creditcard.frauddetection.rules.FraudRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FraudDetectionService {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionService.class);

    private final List<FraudRule> rules;
    private final FraudAlertRepository fraudAlertRepository;
    private final TransactionRecordRepository transactionRecordRepository;

    public FraudDetectionService(List<FraudRule> rules,
                                  FraudAlertRepository fraudAlertRepository,
                                  TransactionRecordRepository transactionRecordRepository) {
        this.rules = rules;
        this.fraudAlertRepository = fraudAlertRepository;
        this.transactionRecordRepository = transactionRecordRepository;
    }

    @Transactional
    public Optional<FraudAlertRaisedEvent> evaluate(TransactionInitiatedEvent event) {
        List<String> triggered = rules.stream()
                .map(rule -> safeEvaluate(rule, event))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        TransactionRecord record = new TransactionRecord();
        record.setId(UUID.randomUUID());
        record.setTransactionId(event.getTransactionId());
        record.setCardToken(event.getCardToken());
        record.setAmount(event.getAmount());
        record.setCurrencyCode(event.getCurrencyCode());
        record.setOccurredAt(event.getOccurredAt());
        transactionRecordRepository.save(record);

        if (triggered.isEmpty()) {
            return Optional.empty();
        }

        FraudAlert alert = new FraudAlert();
        alert.setId(UUID.randomUUID());
        alert.setTransactionId(event.getTransactionId());
        alert.setCardToken(event.getCardToken());
        alert.setAmount(event.getAmount());
        alert.setCurrencyCode(event.getCurrencyCode());
        alert.setTriggeredRules(triggered);
        alert.setDetectedAt(Instant.now());
        FraudAlert saved = fraudAlertRepository.save(alert);

        return Optional.of(FraudAlertRaisedEvent.of(
                saved.getId(),
                event.getTransactionId(),
                event.getCardToken(),
                triggered,
                saved.getDetectedAt(),
                MDC.get("traceId")
        ));
    }

    private Optional<String> safeEvaluate(FraudRule rule, TransactionInitiatedEvent event) {
        try {
            return rule.evaluate(event);
        } catch (Exception e) {
            log.warn("Fraud rule {} threw exception, treating as not triggered: {}",
                    rule.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }
}
