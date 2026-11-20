package com.creditcard.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

@Configuration
public class RateLimitConfig {

    /**
     * Rate-limit key: the JWT subject when the request is authenticated, otherwise the
     * client IP. The IP fallback is what lets the public guest routes (no JWT) be
     * limited at all -- before it, a request with no subject got an empty key, which
     * the gateway treats as "deny". This uses the socket's remote address only; it
     * does not read X-Forwarded-For, which a client can forge (see docs/BACKLOG.md).
     */
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
                    .switchIfEmpty(Mono.fromSupplier(() -> routePrefix + "ip:" + clientIp(exchange)));
        };
    }

    static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        return remote.getAddress().getHostAddress();
    }

    /** Limiter for JWT-authenticated (staff and internal) routes. */
    @Primary
    @Bean
    public RedisRateLimiter defaultRedisRateLimiter(
            @Value("${gateway.rate-limit.replenish-rate:10}") int replenishRate,
            @Value("${gateway.rate-limit.burst-capacity:20}") int burstCapacity) {
        return new RedisRateLimiter(replenishRate, burstCapacity, 1);
    }

    /** Stricter limiter for the public guest routes, keyed by client IP. */
    @Bean
    public RedisRateLimiter publicRedisRateLimiter(
            @Value("${gateway.rate-limit.public.replenish-rate:5}") int replenishRate,
            @Value("${gateway.rate-limit.public.burst-capacity:10}") int burstCapacity) {
        return new RedisRateLimiter(replenishRate, burstCapacity, 1);
    }
}
