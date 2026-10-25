package com.creditcard.gateway.config;

import com.creditcard.gateway.config.logging.PanMaskingFilter;
import com.creditcard.gateway.exception.GatewayAuthExceptionHandler;
import com.creditcard.gateway.fallback.FallbackController;
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
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(controllers = FallbackController.class)
@Import({SecurityConfig.class, GatewayAuthExceptionHandler.class,
         AuditLoggingFilter.class, IdentityPropagationFilter.class,
         MerchantScopeEnforcementFilter.class})
class SecurityConfigTest {

    @Autowired
    private WebTestClient webClient;

    // FallbackController now needs a MeterRegistry; see FallbackControllerTest
    // for why this must be the concrete SimpleMeterRegistry type with no
    // field initializer (both avoid distinct bean-resolution failures).
    @SpyBean
    private SimpleMeterRegistry meterRegistry;

    @MockBean
    private ReactiveJwtDecoder jwtDecoder;

    @MockBean
    private PanMaskingFilter panMaskingFilter;

    @Test
    void noToken_returns401() {
        webClient.post().uri("/api/v1/transactions")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void merchantToken_postTransactions_notForbidden() {
        webClient.mutateWith(mockJwt()
                        .authorities(() -> "ROLE_MERCHANT")
                        .jwt(j -> j.claim("sub", "merchant-001").claim("merchantId", "m-001")))
                .post().uri("/api/v1/transactions")
                .exchange()
                .expectStatus().isNotFound(); // 404 because no route handler in slice, but NOT 401/403
    }

    @Test
    void supportToken_postTransactions_returns403() {
        webClient.mutateWith(mockJwt().authorities(() -> "ROLE_SUPPORT"))
                .post().uri("/api/v1/transactions")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void adminToken_allMerchantsSummary_notForbidden() {
        webClient.mutateWith(mockJwt().authorities(() -> "ROLE_ADMIN"))
                .get().uri("/api/v1/reporting/merchants/summary")
                .exchange()
                .expectStatus().isNotFound(); // not 401/403
    }

    @Test
    void merchantToken_allMerchantsSummary_returns403() {
        webClient.mutateWith(mockJwt()
                        .authorities(() -> "ROLE_MERCHANT")
                        .jwt(j -> j.claim("merchantId", "m-001")))
                .get().uri("/api/v1/reporting/merchants/summary")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void noToken_actuatorHealth_notBlocked() {
        // WebFluxTest with controllers=FallbackController.class does not load actuator endpoints,
        // so /actuator/health returns 404. The key assertion is that security does NOT block it (no 401).
        webClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void noToken_fallback_permitAll() {
        webClient.get().uri("/fallback/transaction")
                .exchange()
                .expectStatus().is5xxServerError(); // 503, not 401
    }

    @Test
    void merchantToken_settlementsRoute_returns403() {
        webClient.mutateWith(mockJwt()
                        .authorities(() -> "ROLE_MERCHANT")
                        .jwt(j -> j.claim("merchantId", "m-001")))
                .get().uri("/api/v1/reporting/settlements/batch-001")
                .exchange()
                .expectStatus().isForbidden();
    }
}
