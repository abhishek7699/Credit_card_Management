package com.creditcard.frauddetection.rules;

import com.creditcard.frauddetection.domain.FraudRuleConfig;
import com.creditcard.frauddetection.kafka.event.TransactionInitiatedEvent;
import com.creditcard.frauddetection.repository.FraudRuleConfigRepository;
import com.creditcard.frauddetection.repository.TransactionRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AmountAnomalyRuleTest {

    @Mock
    private FraudRuleConfigRepository ruleConfigRepository;

    @Mock
    private TransactionRecordRepository transactionRecordRepository;

    private AmountAnomalyRule anomalyRule;

    @BeforeEach
    void setUp() {
        anomalyRule = new AmountAnomalyRule(ruleConfigRepository, transactionRecordRepository, new ObjectMapper());
    }

    @Test
    void shouldFireWhenAmountExceedsMultiplierTimesAverage() {
        when(ruleConfigRepository.findById("AMOUNT_ANOMALY"))
                .thenReturn(Optional.of(buildConfig(true, "{\"multiplier\": 3.0, \"lookbackDays\": 30, \"absoluteCeiling\": 10000.00}")));
        when(transactionRecordRepository.findAverageAmountByCardTokenAndOccurredAtAfter(eq("vault:v1:tok"), any()))
                .thenReturn(Optional.of(new BigDecimal("100.00")));

        Optional<String> result = anomalyRule.evaluate(buildEvent("vault:v1:tok", "350.00"));

        assertThat(result).isPresent().contains("AMOUNT_ANOMALY");
    }

    @Test
    void shouldNotFireWhenAmountWithinThreshold() {
        when(ruleConfigRepository.findById("AMOUNT_ANOMALY"))
                .thenReturn(Optional.of(buildConfig(true, "{\"multiplier\": 3.0, \"lookbackDays\": 30, \"absoluteCeiling\": 10000.00}")));
        when(transactionRecordRepository.findAverageAmountByCardTokenAndOccurredAtAfter(eq("vault:v1:tok"), any()))
                .thenReturn(Optional.of(new BigDecimal("100.00")));

        Optional<String> result = anomalyRule.evaluate(buildEvent("vault:v1:tok", "200.00"));

        assertThat(result).isEmpty();
    }

    @Test
    void shouldUseAbsoluteCeilingWhenNoTransactionHistory() {
        when(ruleConfigRepository.findById("AMOUNT_ANOMALY"))
                .thenReturn(Optional.of(buildConfig(true, "{\"multiplier\": 1.0, \"lookbackDays\": 30, \"absoluteCeiling\": 500.00}")));
        when(transactionRecordRepository.findAverageAmountByCardTokenAndOccurredAtAfter(eq("vault:v1:tok"), any()))
                .thenReturn(Optional.empty());

        Optional<String> aboveCeiling = anomalyRule.evaluate(buildEvent("vault:v1:tok", "600.00"));
        assertThat(aboveCeiling).isPresent().contains("AMOUNT_ANOMALY");
    }

    @Test
    void shouldNotFireWhenBelowAbsoluteCeiling() {
        when(ruleConfigRepository.findById("AMOUNT_ANOMALY"))
                .thenReturn(Optional.of(buildConfig(true, "{\"multiplier\": 1.0, \"lookbackDays\": 30, \"absoluteCeiling\": 500.00}")));
        when(transactionRecordRepository.findAverageAmountByCardTokenAndOccurredAtAfter(eq("vault:v1:tok"), any()))
                .thenReturn(Optional.empty());

        Optional<String> belowCeiling = anomalyRule.evaluate(buildEvent("vault:v1:tok", "400.00"));
        assertThat(belowCeiling).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenRuleDisabled() {
        when(ruleConfigRepository.findById("AMOUNT_ANOMALY"))
                .thenReturn(Optional.of(buildConfig(false, "{\"multiplier\": 1.0, \"lookbackDays\": 30, \"absoluteCeiling\": 1.00}")));

        assertThat(anomalyRule.evaluate(buildEvent("vault:v1:tok", "999999.00"))).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenConfigMissing() {
        when(ruleConfigRepository.findById("AMOUNT_ANOMALY")).thenReturn(Optional.empty());

        assertThat(anomalyRule.evaluate(buildEvent("vault:v1:tok", "50.00"))).isEmpty();
    }

    @Test
    void shouldThrowWhenParametersJsonIsInvalid() {
        when(ruleConfigRepository.findById("AMOUNT_ANOMALY"))
                .thenReturn(Optional.of(buildConfig(true, "not-valid-json")));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> anomalyRule.evaluate(buildEvent("vault:v1:tok", "50.00"))
        );
    }

    private FraudRuleConfig buildConfig(boolean enabled, String params) {
        FraudRuleConfig config = new FraudRuleConfig();
        config.setRuleType("AMOUNT_ANOMALY");
        config.setEnabled(enabled);
        config.setParameters(params);
        config.setUpdatedAt(Instant.now());
        return config;
    }

    private TransactionInitiatedEvent buildEvent(String token, String amount) {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("TransactionInitiated");
        event.setOccurredAt(Instant.now());

        TransactionInitiatedEvent.Payload payload = new TransactionInitiatedEvent.Payload();
        payload.setTransactionId(UUID.randomUUID());
        payload.setCardToken(token);
        payload.setAmount(new BigDecimal(amount));
        payload.setCurrencyCode("USD");
        event.setPayload(payload);
        return event;
    }
}
