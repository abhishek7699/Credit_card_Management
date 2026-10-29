package com.creditcard.gateway.config;

import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CircuitBreakerConfig {

    /**
     * booking-submit's backend call (BookingService#initiatePayment) carries its own
     * 5s gRPC deadline to transaction-service — the gateway's timeout for that one
     * route must exceed it, or the gateway gives up and serves a 503 fallback before
     * the backend's own deadline logic ever gets to run. {@link
     * ReactiveResilience4JCircuitBreakerFactory} has no per-id override on top of
     * {@code configureDefault} (that exists only on the non-reactive factory) — the
     * `id` argument into this function IS the per-route mechanism, so branching on it
     * here is how every other route keeps its unaffected default.
     */
    private static final String BOOKING_SUBMIT_CB = "booking-submit-cb";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration BOOKING_SUBMIT_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCircuitBreakerCustomizer() {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(timeoutFor(id))
                        .build())
                .circuitBreakerConfig(
                        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.ofDefaults())
                .build());
    }

    Duration timeoutFor(String id) {
        return BOOKING_SUBMIT_CB.equals(id) ? BOOKING_SUBMIT_TIMEOUT : DEFAULT_TIMEOUT;
    }
}
