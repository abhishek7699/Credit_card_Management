package com.creditcard.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityPropagationFilterTest {

    private IdentityPropagationFilter filter;
    private AtomicReference<ServerWebExchange> chainExchange;
    private WebFilterChain capturingChain;

    @BeforeEach
    void setUp() {
        filter = new IdentityPropagationFilter();
        chainExchange = new AtomicReference<>();
        capturingChain = exchange -> {
            chainExchange.set(exchange);
            return Mono.empty();
        };
    }

    private Jwt buildJwt(String sub, String role, String merchantId) {
        var builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", sub)
                .claim("authorities", List.of(role))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
        if (merchantId != null) {
            builder.claim("merchantId", merchantId);
        }
        return builder.build();
    }

    private Mono<Void> filterWithAuth(ServerWebExchange exchange, Jwt jwt, String role) {
        var auth = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)));
        SecurityContext ctx = new SecurityContextImpl(auth);
        return filter.filter(exchange, capturingChain)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(ctx)));
    }

    @Test
    void merchantRole_setsAllThreeHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions").build());
        Jwt jwt = buildJwt("user-123", "ROLE_MERCHANT", "merchant-001");

        StepVerifier.create(filterWithAuth(exchange, jwt, "ROLE_MERCHANT"))
                .verifyComplete();

        assertThat(chainExchange.get()).isNotNull();
        var headers = chainExchange.get().getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-Sub")).isEqualTo("user-123");
        assertThat(headers.getFirst("X-User-Role")).isEqualTo("MERCHANT");
        assertThat(headers.getFirst("X-Merchant-Id")).isEqualTo("merchant-001");
    }

    @Test
    void adminRole_noMerchantIdHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/reporting/merchants/summary").build());
        Jwt jwt = buildJwt("admin-001", "ROLE_ADMIN", null);

        StepVerifier.create(filterWithAuth(exchange, jwt, "ROLE_ADMIN"))
                .verifyComplete();

        assertThat(chainExchange.get()).isNotNull();
        var headers = chainExchange.get().getRequest().getHeaders();
        assertThat(headers.getFirst("X-User-Sub")).isEqualTo("admin-001");
        assertThat(headers.getFirst("X-User-Role")).isEqualTo("ADMIN");
        assertThat(headers.getFirst("X-Merchant-Id")).isNull();
    }

    @Test
    void supportRole_noMerchantIdHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/reporting/transactions").build());
        Jwt jwt = buildJwt("support-001", "ROLE_SUPPORT", null);

        StepVerifier.create(filterWithAuth(exchange, jwt, "ROLE_SUPPORT"))
                .verifyComplete();

        assertThat(chainExchange.get()).isNotNull();
        assertThat(chainExchange.get().getRequest().getHeaders().getFirst("X-User-Role")).isEqualTo("SUPPORT");
        assertThat(chainExchange.get().getRequest().getHeaders().getFirst("X-Merchant-Id")).isNull();
    }

    @Test
    void unauthenticated_noHeadersInjected_chainContinues() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/public").build());

        StepVerifier.create(filter.filter(exchange, capturingChain))
                .verifyComplete();

        // Chain is still called (with original exchange — no mutation for unauthenticated)
        assertThat(chainExchange.get()).isNotNull();
        assertThat(chainExchange.get().getRequest().getHeaders().getFirst("X-User-Sub")).isNull();
    }

    @Test
    void merchantRole_merchantIdSetExactly() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions").build());
        Jwt jwt = buildJwt("m-sub", "ROLE_MERCHANT", "merchant-xyz");

        StepVerifier.create(filterWithAuth(exchange, jwt, "ROLE_MERCHANT"))
                .verifyComplete();

        assertThat(chainExchange.get().getRequest().getHeaders().getFirst("X-Merchant-Id"))
                .isEqualTo("merchant-xyz");
    }

    @Test
    void merchantRole_blankMerchantId_omitsMerchantIdHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions").build());
        Jwt jwt = buildJwt("user-123", "ROLE_MERCHANT", "  "); // blank, not null

        StepVerifier.create(filterWithAuth(exchange, jwt, "ROLE_MERCHANT"))
                .verifyComplete();

        assertThat(chainExchange.get()).isNotNull();
        assertThat(chainExchange.get().getRequest().getHeaders().getFirst("X-Merchant-Id")).isNull();
    }

    @Test
    void merchantRole_nullSubject_setsEmptySubHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions").build());

        // Build JWT with no "sub" claim → getSubject() returns null
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("merchantId", "merchant-001")
                .build();

        var auth = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
        SecurityContext ctx = new SecurityContextImpl(auth);

        StepVerifier.create(filter.filter(exchange, capturingChain)
                        .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(ctx))))
                .verifyComplete();

        assertThat(chainExchange.get()).isNotNull();
        assertThat(chainExchange.get().getRequest().getHeaders().getFirst("X-User-Sub")).isEqualTo("");
    }
}
