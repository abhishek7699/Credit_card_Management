package com.creditcard.frauddetection.rules;

import com.creditcard.frauddetection.domain.FraudRuleConfig;
import com.creditcard.frauddetection.kafka.event.TransactionInitiatedEvent;
import com.creditcard.frauddetection.repository.BlacklistEntryRepository;
import com.creditcard.frauddetection.repository.FraudRuleConfigRepository;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlacklistRuleTest {

    @Mock
    private FraudRuleConfigRepository ruleConfigRepository;

    @Mock
    private BlacklistEntryRepository blacklistEntryRepository;

    private BlacklistRule blacklistRule;

    @BeforeEach
    void setUp() {
        blacklistRule = new BlacklistRule(ruleConfigRepository, blacklistEntryRepository);
    }

    @Test
    void shouldFireWhenCardIsBlacklisted() {
        when(ruleConfigRepository.findById("BLACKLISTED_CARD"))
                .thenReturn(Optional.of(buildConfig(true)));
        when(blacklistEntryRepository.existsByCardToken("vault:v1:blocked")).thenReturn(true);

        Optional<String> result = blacklistRule.evaluate(buildEvent("vault:v1:blocked"));

        assertThat(result).isPresent().contains("BLACKLISTED_CARD");
    }

    @Test
    void shouldNotFireWhenCardNotBlacklisted() {
        when(ruleConfigRepository.findById("BLACKLISTED_CARD"))
                .thenReturn(Optional.of(buildConfig(true)));
        when(blacklistEntryRepository.existsByCardToken("vault:v1:clean")).thenReturn(false);

        Optional<String> result = blacklistRule.evaluate(buildEvent("vault:v1:clean"));

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenRuleDisabled() {
        when(ruleConfigRepository.findById("BLACKLISTED_CARD"))
                .thenReturn(Optional.of(buildConfig(false)));

        assertThat(blacklistRule.evaluate(buildEvent("vault:v1:blocked"))).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenConfigMissing() {
        when(ruleConfigRepository.findById("BLACKLISTED_CARD")).thenReturn(Optional.empty());

        assertThat(blacklistRule.evaluate(buildEvent("vault:v1:tok"))).isEmpty();
    }

    private FraudRuleConfig buildConfig(boolean enabled) {
        FraudRuleConfig config = new FraudRuleConfig();
        config.setRuleType("BLACKLISTED_CARD");
        config.setEnabled(enabled);
        config.setParameters("{}");
        config.setUpdatedAt(Instant.now());
        return config;
    }

    private TransactionInitiatedEvent buildEvent(String token) {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("TransactionInitiated");
        event.setOccurredAt(Instant.now());

        TransactionInitiatedEvent.Payload payload = new TransactionInitiatedEvent.Payload();
        payload.setTransactionId(UUID.randomUUID());
        payload.setCardToken(token);
        payload.setAmount(new BigDecimal("100.00"));
        payload.setCurrencyCode("USD");
        event.setPayload(payload);
        return event;
    }
}
