package com.creditcard.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerConfigTest {

    private ReactiveResilience4JCircuitBreakerFactory factory;
    private TimeLimiterRegistry timeLimiterRegistry;

    @BeforeEach
    void setUp() {
        timeLimiterRegistry = TimeLimiterRegistry.ofDefaults();
        factory = new ReactiveResilience4JCircuitBreakerFactory(
                CircuitBreakerRegistry.ofDefaults(),
                timeLimiterRegistry);
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

    @Test
    void bookingServiceCbCanBeCreated() {
        assertThat(factory.create("booking-service-cb")).isNotNull();
    }

    @Test
    void bookingSubmitCbCanBeCreated() {
        assertThat(factory.create("booking-submit-cb")).isNotNull();
    }

    // timeoutFor(id) is the actual per-route decision (see its Javadoc for why: the
    // reactive factory has no per-id override on top of configureDefault, so this
    // function IS the per-route mechanism). Verified directly rather than via the
    // factory/registries: ReactiveResilience4JCircuitBreaker only registers a config
    // under a given id lazily, on first real use (.run(...)) — querying the shared
    // TimeLimiterRegistry right after factory.create(id) observes the registry's own
    // 1s default instead, not what this bean would have configured.
    @Test
    void timeoutFor_bookingSubmitCb_isTenSeconds_exceedingBookingServicesFiveSecondGrpcDeadline() {
        assertThat(new CircuitBreakerConfig().timeoutFor("booking-submit-cb")).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void timeoutFor_otherRoutes_isTheDefaultFiveSeconds() {
        CircuitBreakerConfig config = new CircuitBreakerConfig();
        assertThat(config.timeoutFor("booking-service-cb")).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.timeoutFor("transaction-service-cb")).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.timeoutFor("reporting-service-cb")).isEqualTo(Duration.ofSeconds(5));
    }
}
