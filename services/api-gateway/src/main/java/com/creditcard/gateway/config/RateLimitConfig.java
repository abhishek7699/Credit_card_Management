package com.creditcard.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimitConfig {

    @Bean
    public KeyResolver jwtSubjectKeyResolver() {
        return exchange -> {
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String routePrefix = (route != null) ? route.getId() + "::" : "";
            return ReactiveSecurityContextHolder.getContext()
                    .flatMap(ctx -> {
                        if (ctx.getAuthentication() instanceof JwtAuthenticationToken jwtAuth) {
                            String sub = jwtAuth.getToken().getSubject();
                            return sub != null ? Mono.just(routePrefix + sub) : Mono.empty();
                        }
                        return Mono.empty();
                    })
                    .switchIfEmpty(Mono.empty());
        };
    }

    @Bean
    public RedisRateLimiter defaultRedisRateLimiter(
            @Value("${gateway.rate-limit.replenish-rate:10}") int replenishRate,
            @Value("${gateway.rate-limit.burst-capacity:20}") int burstCapacity) {
        return new RedisRateLimiter(replenishRate, burstCapacity, 1);
    }
}
