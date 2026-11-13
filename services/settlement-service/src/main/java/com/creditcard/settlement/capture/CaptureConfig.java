package com.creditcard.settlement.capture;

import com.creditcard.mastercard.MastercardGatewayClient;
import com.creditcard.mastercard.MastercardGatewayConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * Chooses the capture gateway from {@code payment.processor}, the same setting authorization-service uses.
 * {@code simulated} (default) captures nothing. {@code mastercard} captures at Mastercard. Anything else fails at startup.
 */
@Configuration
public class CaptureConfig {

    @Bean
    public CaptureGateway captureGateway(
            @Value("${payment.processor:simulated}") String processorName,
            @Value("${mastercard.base-url:}") String baseUrl,
            @Value("${mastercard.merchant-id:}") String merchantId,
            @Value("${mastercard.api-password:}") String apiPassword,
            @Value("${mastercard.api-version:100}") String apiVersion,
            @Value("${mastercard.connect-timeout-seconds:5}") long connectTimeoutSeconds,
            @Value("${mastercard.read-timeout-seconds:10}") long readTimeoutSeconds) throws NoSuchAlgorithmException {
        return switch (processorName) {
            case "simulated" -> new SimulatedCaptureGateway();
            case "mastercard" -> new MastercardCaptureGateway(new MastercardGatewayClient(
                    new MastercardGatewayConfig(baseUrl, merchantId, apiPassword, apiVersion,
                            Duration.ofSeconds(connectTimeoutSeconds), Duration.ofSeconds(readTimeoutSeconds)),
                    SSLContext.getDefault()));
            default -> throw new IllegalArgumentException(
                    "payment.processor must be 'simulated' or 'mastercard', was '" + processorName + "'");
        };
    }
}
