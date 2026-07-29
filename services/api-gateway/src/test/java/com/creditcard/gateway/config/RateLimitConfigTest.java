package com.creditcard.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitConfigTest {

    private RateLimitConfig config;
    private KeyResolver keyResolver;

    @BeforeEach
    void setUp() {
        config = new RateLimitConfig();
        keyResolver = config.jwtSubjectKeyResolver();
    }

    private Jwt buildJwt(String sub) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", sub)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    @Test
    void authenticatedUser_returnsSubAsPrincipal() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/transactions").build());
        var jwt = buildJwt("user-123");
        var auth = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
        var ctx = new SecurityContextImpl(auth);

        StepVerifier.create(
                keyResolver.resolve(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                                reactor.core.publisher.Mono.just(ctx))))
                .expectNext("user-123")
                .verifyComplete();
    }

    @Test
    void unauthenticated_returnsEmptyMono() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/transactions").build());

        StepVerifier.create(keyResolver.resolve(exchange))
                .verifyComplete();
    }

    @Test
    void nonJwtPrincipal_returnsEmptyMono() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test").build());
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "user", "pass");
        var ctx = new SecurityContextImpl(auth);

        StepVerifier.create(
                keyResolver.resolve(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                                reactor.core.publisher.Mono.just(ctx))))
                .verifyComplete();
    }

    @Test
    void defaultRedisRateLimiter_configuresReplenishRateAndBurstCapacity() {
        RedisRateLimiter rateLimiter = config.defaultRedisRateLimiter(10, 20);
        assertThat(rateLimiter).isNotNull();
    }

    @Test
    void authenticatedUser_withRoute_includesRouteInKey() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/transactions").build());
        Route route = mock(Route.class);
        when(route.getId()).thenReturn("transaction-submit");
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);

        var jwt = buildJwt("user-123");
        var auth = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
        var ctx = new SecurityContextImpl(auth);

        StepVerifier.create(
                keyResolver.resolve(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                                reactor.core.publisher.Mono.just(ctx))))
                .expectNext("transaction-submit::user-123")
                .verifyComplete();
    }

    @Test
    void jwtWithoutSubject_returnsEmptyMono() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api").build());
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("scope", "read")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        var auth = new JwtAuthenticationToken(jwt, List.of());
        var ctx = new SecurityContextImpl(auth);

        StepVerifier.create(
                keyResolver.resolve(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                                reactor.core.publisher.Mono.just(ctx))))
                .verifyComplete();
    }

    @Test
    void differentUsers_returnDifferentKeys() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/transactions").build());
        var jwt1 = buildJwt("client-A");
        var auth1 = new JwtAuthenticationToken(jwt1, List.of());
        var ctx1 = new SecurityContextImpl(auth1);

        var jwt2 = buildJwt("client-B");
        var auth2 = new JwtAuthenticationToken(jwt2, List.of());
        var ctx2 = new SecurityContextImpl(auth2);

        StepVerifier.create(
                keyResolver.resolve(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                                reactor.core.publisher.Mono.just(ctx1))))
                .expectNext("client-A")
                .verifyComplete();

        StepVerifier.create(
                keyResolver.resolve(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                                reactor.core.publisher.Mono.just(ctx2))))
                .expectNext("client-B")
                .verifyComplete();
    }
}
