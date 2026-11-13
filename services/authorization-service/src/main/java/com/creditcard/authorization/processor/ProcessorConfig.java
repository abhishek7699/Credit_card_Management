package com.creditcard.authorization.processor;

import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import com.creditcard.authorization.repository.CardAccountRepository;
import com.creditcard.authorization.repository.TimeoutMarkerRepository;
import com.creditcard.mastercard.MastercardGatewayClient;
import com.creditcard.mastercard.MastercardGatewayConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * Creates the one {@link PaymentProcessor} bean from {@code payment.processor}:
 * {@code simulated} (default) or {@code mastercard}. Any other value fails at startup.
 *
 * <p>The Mastercard settings are read only when that processor is selected, so the simulator never needs them.
 * Trust for the gateway is the JVM's default trust store, which verifies certificates.
 */
@Configuration
public class ProcessorConfig {

    @Bean
    public PaymentProcessor paymentProcessor(
            @Value("${payment.processor:simulated}") String processorName,
            CardAccountRepository cardAccountRepository,
            AuthorizationRecordRepository authorizationRecordRepository,
            TimeoutMarkerRepository timeoutMarkerRepository,
            @Value("${mastercard.base-url:}") String baseUrl,
            @Value("${mastercard.merchant-id:}") String merchantId,
            @Value("${mastercard.api-password:}") String apiPassword,
            @Value("${mastercard.api-version:100}") String apiVersion,
            @Value("${mastercard.connect-timeout-seconds:5}") long connectTimeoutSeconds,
            @Value("${mastercard.read-timeout-seconds:10}") long readTimeoutSeconds) throws NoSuchAlgorithmException {
        return switch (processorName) {
            case "simulated" -> new SimulatedProcessor(
                    cardAccountRepository, authorizationRecordRepository, timeoutMarkerRepository);
            case "mastercard" -> new MastercardGatewayProcessor(
                    new MastercardGatewayClient(
                            new MastercardGatewayConfig(baseUrl, merchantId, apiPassword, apiVersion,
                                    Duration.ofSeconds(connectTimeoutSeconds), Duration.ofSeconds(readTimeoutSeconds)),
                            SSLContext.getDefault()),
                    authorizationRecordRepository, timeoutMarkerRepository);
            default -> throw new IllegalArgumentException(
                    "payment.processor must be 'simulated' or 'mastercard', was '" + processorName + "'");
        };
    }
}
