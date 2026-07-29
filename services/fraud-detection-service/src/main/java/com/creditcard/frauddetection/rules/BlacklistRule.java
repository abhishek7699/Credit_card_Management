package com.creditcard.frauddetection.rules;

import com.creditcard.frauddetection.domain.FraudRuleConfig;
import com.creditcard.frauddetection.kafka.event.TransactionInitiatedEvent;
import com.creditcard.frauddetection.repository.BlacklistEntryRepository;
import com.creditcard.frauddetection.repository.FraudRuleConfigRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class BlacklistRule implements FraudRule {

    private static final String RULE_TYPE = "BLACKLISTED_CARD";

    private final FraudRuleConfigRepository ruleConfigRepository;
    private final BlacklistEntryRepository blacklistEntryRepository;

    public BlacklistRule(FraudRuleConfigRepository ruleConfigRepository,
                         BlacklistEntryRepository blacklistEntryRepository) {
        this.ruleConfigRepository = ruleConfigRepository;
        this.blacklistEntryRepository = blacklistEntryRepository;
    }

    @Override
    public Optional<String> evaluate(TransactionInitiatedEvent event) {
        Optional<FraudRuleConfig> configOpt = ruleConfigRepository.findById(RULE_TYPE);
        if (configOpt.isEmpty() || !configOpt.get().isEnabled()) {
            return Optional.empty();
        }

        if (blacklistEntryRepository.existsByCardToken(event.getCardToken())) {
            return Optional.of(RULE_TYPE);
        }
        return Optional.empty();
    }
}
