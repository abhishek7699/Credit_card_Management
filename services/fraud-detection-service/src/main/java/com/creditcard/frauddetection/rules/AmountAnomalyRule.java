package com.creditcard.frauddetection.rules;

import com.creditcard.frauddetection.domain.FraudRuleConfig;
import com.creditcard.frauddetection.kafka.event.TransactionInitiatedEvent;
import com.creditcard.frauddetection.repository.FraudRuleConfigRepository;
import com.creditcard.frauddetection.repository.TransactionRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Component
public class AmountAnomalyRule implements FraudRule {

    private static final String RULE_TYPE = "AMOUNT_ANOMALY";

    private final FraudRuleConfigRepository ruleConfigRepository;
    private final TransactionRecordRepository transactionRecordRepository;
    private final ObjectMapper objectMapper;

    public AmountAnomalyRule(FraudRuleConfigRepository ruleConfigRepository,
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

        AnomalyParams params = parseParams(configOpt.get().getParameters());
        Instant cutoff = Instant.now().minus(params.lookbackDays(), ChronoUnit.DAYS);

        Optional<BigDecimal> avgOpt = transactionRecordRepository
                .findAverageAmountByCardTokenAndOccurredAtAfter(event.getPayload().getCardToken(), cutoff);

        BigDecimal baseline = avgOpt.orElse(params.absoluteCeiling());
        BigDecimal threshold = baseline.multiply(BigDecimal.valueOf(params.multiplier()));

        if (event.getPayload().getAmount().compareTo(threshold) > 0) {
            return Optional.of(RULE_TYPE);
        }
        return Optional.empty();
    }

    private AnomalyParams parseParams(String json) {
        try {
            return objectMapper.readValue(json, AnomalyParams.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse AmountAnomalyRule parameters: " + json, e);
        }
    }

    record AnomalyParams(double multiplier, int lookbackDays, BigDecimal absoluteCeiling) {}
}
