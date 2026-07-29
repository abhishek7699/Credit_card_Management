package com.creditcard.gateway.fallback;

import com.creditcard.gateway.config.SecurityConfig;
import com.creditcard.gateway.config.logging.PanMaskingFilter;
import com.creditcard.gateway.exception.GatewayAuthExceptionHandler;
import com.creditcard.gateway.filter.AuditLoggingFilter;
import com.creditcard.gateway.filter.IdentityPropagationFilter;
import com.creditcard.gateway.filter.MerchantScopeEnforcementFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(controllers = FallbackController.class)
@Import({SecurityConfig.class, GatewayAuthExceptionHandler.class,
         AuditLoggingFilter.class, IdentityPropagationFilter.class,
         MerchantScopeEnforcementFilter.class})
class FallbackControllerTest {

    @Autowired
    private WebTestClient webClient;

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
}
