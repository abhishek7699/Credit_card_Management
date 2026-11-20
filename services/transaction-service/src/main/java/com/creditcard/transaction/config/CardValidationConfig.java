package com.creditcard.transaction.config;

import com.creditcard.mastercard.developers.AccountValidationClient;
import com.creditcard.mastercard.developers.MastercardDevelopersConfig;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the Mastercard Account Validation client only when {@code card-validation.enabled=true}
 * (the dev profile). Credentials come from the MASTERCARD_DEV_* environment variables only. Enabling the
 * switch without them fails startup, naming the missing variable, instead of failing a guest's booking.
 */
@Configuration
public class CardValidationConfig {

    @Bean
    @ConditionalOnProperty(name = "card-validation.enabled", havingValue = "true")
    public AccountValidationClient accountValidationClient(
            @Value("${card-validation.timeout-seconds:6}") long timeoutSeconds) throws Exception {
        return buildClient(System.getenv(), Duration.ofSeconds(timeoutSeconds));
    }

    static AccountValidationClient buildClient(Map<String, String> env, Duration timeout) throws Exception {
        return new AccountValidationClient(MastercardDevelopersConfig.fromEnvironment(env), timeout);
    }
}
