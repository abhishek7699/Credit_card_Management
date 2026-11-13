package com.creditcard.authorization.processor;

import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import com.creditcard.authorization.repository.CardAccountRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the one {@link PaymentProcessor} bean. Phase 1: always the simulator. Phase 2 (spec 014) adds
 * selection by {@code payment.processor}.
 */
@Configuration
public class ProcessorConfig {

    @Bean
    public PaymentProcessor paymentProcessor(CardAccountRepository cardAccountRepository,
                                             AuthorizationRecordRepository authorizationRecordRepository) {
        return new SimulatedProcessor(cardAccountRepository, authorizationRecordRepository);
    }
}
