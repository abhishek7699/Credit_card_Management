package com.creditcard.frauddetection.rules;

import com.creditcard.frauddetection.domain.FraudRuleConfig;
import com.creditcard.frauddetection.kafka.event.TransactionInitiatedEvent;
import com.creditcard.frauddetection.repository.FraudRuleConfigRepository;
import com.creditcard.frauddetection.repository.TransactionRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Component
public class VelocityRule implements FraudRule {

    private static final String RULE_TYPE = "VELOCITY_EXCEEDED";

    private final FraudRuleConfigRepository ruleConfigRepository;
    private final TransactionRecordRepository transactionRecordRepository;
    private final ObjectMapper objectMapper;

    public VelocityRule(FraudRuleConfigRepository ruleConfigRepository,
                        TransactionRecordRepository transactionRecordRepository,
                        ObjectMapper objectMapper) {
        this.ruleConfigRepository = ruleConfigRepository;
        this.transactionRecordRepository = transactionRecordRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<String> evaluate(TransactionInitiatedEvent event) {
        Optional<FraudRuleConfig> configOpt = ruleConfigRepository.findById(RULE_TYPE);
        if (configOpt.isEmpty() || !configOpt.get().isEnabled()) {
            return Optional.empty();
        }

        VelocityParams params = parseParams(configOpt.get().getParameters());
        Instant cutoff = Instant.now().minus(params.windowMinutes(), ChronoUnit.MINUTES);
        long count = transactionRecordRepository.countByCardTokenAndOccurredAtAfter(event.getCardToken(), cutoff);

        if (count >= params.maxTransactions()) {
            return Optional.of(RULE_TYPE);
        }
        return Optional.empty();
    }

    private VelocityParams parseParams(String json) {
        try {
            return objectMapper.readValue(json, VelocityParams.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse VelocityRule parameters: " + json, e);
        }
    }

    record VelocityParams(int maxTransactions, int windowMinutes) {}
}
