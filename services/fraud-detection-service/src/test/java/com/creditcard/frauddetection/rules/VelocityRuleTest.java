package com.creditcard.frauddetection.rules;

import com.creditcard.frauddetection.domain.FraudRuleConfig;
import com.creditcard.frauddetection.kafka.event.TransactionInitiatedEvent;
import com.creditcard.frauddetection.repository.FraudRuleConfigRepository;
import com.creditcard.frauddetection.repository.TransactionRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
class VelocityRuleTest {

    @Mock
    private FraudRuleConfigRepository ruleConfigRepository;

    @Mock
    private TransactionRecordRepository transactionRecordRepository;

    @InjectMocks
    private VelocityRule velocityRule;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        velocityRule = new VelocityRule(ruleConfigRepository, transactionRecordRepository, objectMapper);
    }

    @Test
    void shouldFireWhenTransactionCountMeetsThreshold() {
        when(ruleConfigRepository.findById("VELOCITY_EXCEEDED"))
                .thenReturn(Optional.of(buildConfig(true, "{\"maxTransactions\": 3, \"windowMinutes\": 10}")));
        when(transactionRecordRepository.countByCardTokenAndOccurredAtAfter(eq("vault:v1:tok"), any()))
                .thenReturn(3L);

        Optional<String> result = velocityRule.evaluate(buildEvent("vault:v1:tok", "50.00"));

        assertThat(result).isPresent().contains("VELOCITY_EXCEEDED");
    }

    @Test
    void shouldNotFireWhenTransactionCountBelowThreshold() {
        when(ruleConfigRepository.findById("VELOCITY_EXCEEDED"))
                .thenReturn(Optional.of(buildConfig(true, "{\"maxTransactions\": 5, \"windowMinutes\": 10}")));
        when(transactionRecordRepository.countByCardTokenAndOccurredAtAfter(eq("vault:v1:tok"), any()))
                .thenReturn(2L);

        Optional<String> result = velocityRule.evaluate(buildEvent("vault:v1:tok", "50.00"));

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenRuleDisabled() {
        when(ruleConfigRepository.findById("VELOCITY_EXCEEDED"))
                .thenReturn(Optional.of(buildConfig(false, "{\"maxTransactions\": 1, \"windowMinutes\": 10}")));

        Optional<String> result = velocityRule.evaluate(buildEvent("vault:v1:tok", "50.00"));

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenConfigMissing() {
        when(ruleConfigRepository.findById("VELOCITY_EXCEEDED")).thenReturn(Optional.empty());

        Optional<String> result = velocityRule.evaluate(buildEvent("vault:v1:tok", "50.00"));

        assertThat(result).isEmpty();
    }

    @Test
    void shouldThrowWhenParametersJsonIsInvalid() {
        when(ruleConfigRepository.findById("VELOCITY_EXCEEDED"))
                .thenReturn(Optional.of(buildConfig(true, "not-valid-json")));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> velocityRule.evaluate(buildEvent("vault:v1:tok", "50.00"))
        );
    }

    private FraudRuleConfig buildConfig(boolean enabled, String params) {
        FraudRuleConfig config = new FraudRuleConfig();
        config.setRuleType("VELOCITY_EXCEEDED");
        config.setEnabled(enabled);
        config.setParameters(params);
        config.setUpdatedAt(Instant.now());
        return config;
    }

    private TransactionInitiatedEvent buildEvent(String token, String amount) {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("TransactionInitiated");
        event.setTransactionId(UUID.randomUUID());
        event.setCardToken(token);
        event.setAmount(new BigDecimal(amount));
        event.setCurrencyCode("USD");
        event.setOccurredAt(Instant.now());
        return event;
    }
}
