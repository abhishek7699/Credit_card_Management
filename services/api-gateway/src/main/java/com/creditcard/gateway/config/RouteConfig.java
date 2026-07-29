package com.creditcard.gateway.config;

import com.creditcard.gateway.filter.GrpcTransactionGatewayFilterFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

@Configuration
public class RouteConfig {

    private final GrpcTransactionGatewayFilterFactory grpcFilter;
    private final KeyResolver keyResolver;
    private final RedisRateLimiter rateLimiter;

    @Value("${REPORTING_SERVICE_URL:http://reporting-service:8086}")
    private String reportingServiceUrl;

    public RouteConfig(GrpcTransactionGatewayFilterFactory grpcFilter,
                       KeyResolver keyResolver,
                       RedisRateLimiter rateLimiter) {
        this.grpcFilter = grpcFilter;
        this.keyResolver = keyResolver;
        this.rateLimiter = rateLimiter;
    }

    @Bean
    public RouteLocator gatewayRoutes(RouteLocatorBuilder builder) {
        return builder.routes()

                // ── Transaction submit (POST) → gRPC InitiateTransaction ────────────
                .route("transaction-submit", r -> r
                        .path("/api/v1/transactions")
                        .and().method(HttpMethod.POST)
                        .filters(f -> f
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(rateLimiter)
                                        .setKeyResolver(keyResolver))
                                .circuitBreaker(c -> c
                                        .setName("transaction-service-cb")
                                        .setFallbackUri("forward:/fallback/transaction"))
                                .filter(grpcFilter.apply(config -> config.setOperation("INITIATE"))))
                        .uri("http://localhost"))

                // ── Transaction get (GET) → gRPC GetTransaction ──────────────────────
                .route("transaction-get", r -> r
                        .path("/api/v1/transactions/{id}")
                        .and().method(HttpMethod.GET)
                        .filters(f -> f
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(rateLimiter)
                                        .setKeyResolver(keyResolver))
                                .circuitBreaker(c -> c
                                        .setName("transaction-service-cb")
                                        .setFallbackUri("forward:/fallback/transaction"))
                                .filter(grpcFilter.apply(config -> config.setOperation("GET"))))
                        .uri("http://localhost"))

                // ── Reporting: transaction history ────────────────────────────────────
                .route("reporting-transactions", r -> r
                        .path("/api/v1/reporting/transactions")
                        .and().method(HttpMethod.GET)
                        .filters(f -> f
                                .rewritePath("/api/v1/reporting/(?<segment>.*)", "/internal/reporting/${segment}")
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(rateLimiter)
                                        .setKeyResolver(keyResolver))
                                .circuitBreaker(c -> c
                                        .setName("reporting-service-cb")
                                        .setFallbackUri("forward:/fallback/reporting")))
                        .uri(reportingServiceUrl))

                // ── Reporting: merchant summary ───────────────────────────────────────
                .route("reporting-merchant-summary", r -> r
                        .path("/api/v1/reporting/merchants/{merchantId}/summary")
                        .and().method(HttpMethod.GET)
                        .filters(f -> f
                                .rewritePath("/api/v1/reporting/(?<segment>.*)", "/internal/reporting/${segment}")
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(rateLimiter)
                                        .setKeyResolver(keyResolver))
                                .circuitBreaker(c -> c
                                        .setName("reporting-service-cb")
                                        .setFallbackUri("forward:/fallback/reporting")))
                        .uri(reportingServiceUrl))

                // ── Reporting: all merchants summary ──────────────────────────────────
                .route("reporting-all-merchants", r -> r
                        .path("/api/v1/reporting/merchants/summary")
                        .and().method(HttpMethod.GET)
                        .filters(f -> f
                                .rewritePath("/api/v1/reporting/(?<segment>.*)", "/internal/reporting/${segment}")
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(rateLimiter)
                                        .setKeyResolver(keyResolver))
                                .circuitBreaker(c -> c
                                        .setName("reporting-service-cb")
                                        .setFallbackUri("forward:/fallback/reporting")))
                        .uri(reportingServiceUrl))

                // ── Reporting: settlement batch ───────────────────────────────────────
                .route("reporting-settlement-batch", r -> r
                        .path("/api/v1/reporting/settlements/{batchId}")
                        .and().method(HttpMethod.GET)
                        .filters(f -> f
                                .rewritePath("/api/v1/reporting/(?<segment>.*)", "/internal/reporting/${segment}")
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(rateLimiter)
                                        .setKeyResolver(keyResolver))
                                .circuitBreaker(c -> c
                                        .setName("reporting-service-cb")
                                        .setFallbackUri("forward:/fallback/reporting")))
                        .uri(reportingServiceUrl))

                // ── Reporting: merchant settlement ────────────────────────────────────
                .route("reporting-merchant-settlement", r -> r
                        .path("/api/v1/reporting/settlements/{batchId}/merchants/{merchantId}")
                        .and().method(HttpMethod.GET)
                        .filters(f -> f
                                .rewritePath("/api/v1/reporting/(?<segment>.*)", "/internal/reporting/${segment}")
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(rateLimiter)
                                        .setKeyResolver(keyResolver))
                                .circuitBreaker(c -> c
                                        .setName("reporting-service-cb")
                                        .setFallbackUri("forward:/fallback/reporting")))
                        .uri(reportingServiceUrl))

                .build();
    }
}
