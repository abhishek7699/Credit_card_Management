package com.creditcard.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerConfigTest {

    private ReactiveResilience4JCircuitBreakerFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ReactiveResilience4JCircuitBreakerFactory(
                CircuitBreakerRegistry.ofDefaults(),
                TimeLimiterRegistry.ofDefaults());
        new CircuitBreakerConfig().defaultCircuitBreakerCustomizer().customize(factory);
    }

    @Test
    void circuitBreakerFactoryBeanLoads() {
        assertThat(factory).isNotNull();
    }

    @Test
    void transactionServiceCbCanBeCreated() {
        assertThat(factory.create("transaction-service-cb")).isNotNull();
    }

    @Test
    void reportingServiceCbCanBeCreated() {
        assertThat(factory.create("reporting-service-cb")).isNotNull();
    }
}
