package com.creditcard.gateway.fallback;

import com.creditcard.gateway.config.SecurityConfig;
import com.creditcard.gateway.config.logging.PanMaskingFilter;
import com.creditcard.gateway.exception.GatewayAuthExceptionHandler;
import com.creditcard.gateway.filter.AuditLoggingFilter;
import com.creditcard.gateway.filter.IdentityPropagationFilter;
import com.creditcard.gateway.filter.MerchantScopeEnforcementFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@WebFluxTest(controllers = FallbackController.class)
@Import({SecurityConfig.class, GatewayAuthExceptionHandler.class,
         AuditLoggingFilter.class, IdentityPropagationFilter.class,
         MerchantScopeEnforcementFilter.class})
class FallbackControllerTest {

    @Autowired
    private WebTestClient webClient;

    // @SpyBean (MockitoPostProcessor-backed, same mechanism as the @MockBeans
    // below) wraps a real SimpleMeterRegistry so Counter.builder(...).register(...)
    // behaves normally. No field initializer: @SpyBean constructs the single
    // instance itself and that instance is cached on the shared test-class
    // ApplicationContext across all @Test methods -- a `= new
    // SimpleMeterRegistry()` initializer here would construct a *different*
    // object on every new JUnit test-instance and conflict with that cached
    // singleton (confirmed: "existing value ... is not the same as new value").
    // Declared as the concrete SimpleMeterRegistry type, not the MeterRegistry
    // interface: when no bean of the declared type exists yet, @SpyBean
    // constructs one via the type's own constructor, and against the bare
    // interface it resolved to PrometheusMeterRegistry (on the classpath
    // here) and failed on an unsatisfiable Clock dependency. A separate
    // @Import-ed @Configuration class providing a MeterRegistry @Bean was
    // tried first and hit the same clash from the other direction:
    // FallbackController silently stopped being registered at all (every
    // request 404'd, no error raised anywhere). SimpleMeterRegistry's own
    // no-arg constructor needs nothing from the context, avoiding all of it.
    @SpyBean
    private SimpleMeterRegistry meterRegistry;

    @MockBean
    private ReactiveJwtDecoder jwtDecoder;

    @MockBean
    private PanMaskingFilter panMaskingFilter;

    @Test
    void fallbackTransaction_returns503() {
        webClient.get().uri("/fallback/transaction")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void fallbackTransaction_bodyContainsServiceUnavailable() {
        webClient.get().uri("/fallback/transaction")
                .exchange()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Service Unavailable")
                .jsonPath("$.message").value(msg ->
                        org.assertj.core.api.Assertions.assertThat(msg.toString())
                                .containsIgnoringCase("transaction"));
    }

    @Test
    void fallbackReporting_returns503() {
        webClient.get().uri("/fallback/reporting")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void fallbackReporting_contentTypeIsJson() {
        webClient.get().uri("/fallback/reporting")
                .exchange()
                .expectHeader().contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void fallbackTransaction_post_returns503() {
        webClient.post().uri("/fallback/transaction")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.error").isEqualTo("Service Unavailable");
    }

    @Test
    void fallbackUnknownService_returns503WithGenericMessage() {
        webClient.get().uri("/fallback/unknown-service")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.message").value(msg ->
                        org.assertj.core.api.Assertions.assertThat(msg.toString())
                                .containsIgnoringCase("unavailable"));
    }

    @Test
    void knownServiceCountersAreRegisteredEagerlyAtStartup() {
        // Registration happens in the constructor, before any request --
        // asserting existence (not a specific count, since other tests in
        // this shared Spring context also increment these same counters)
        // proves the warm-up ran at boot rather than lazily on first use.
        assertThat(meterRegistry.find("gateway.fallback.requests").tag("service", "transaction").counter())
                .isNotNull();
        assertThat(meterRegistry.find("gateway.fallback.requests").tag("service", "reporting").counter())
                .isNotNull();
    }

    @Test
    void fallbackTransaction_incrementsTransactionCounterByOne() {
        double before = meterRegistry.get("gateway.fallback.requests")
                .tag("service", "transaction").counter().count();

        webClient.get().uri("/fallback/transaction")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        double after = meterRegistry.get("gateway.fallback.requests")
                .tag("service", "transaction").counter().count();
        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    void fallbackUnknownService_registersAndIncrementsItsOwnCounter() {
        webClient.get().uri("/fallback/a-service-only-this-test-uses")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(meterRegistry.get("gateway.fallback.requests")
                .tag("service", "a-service-only-this-test-uses").counter().count())
                .isEqualTo(1);
    }
}
