package com.creditcard.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class IdentityPropagationFilter implements WebFilter, Ordered {

    private static final SecurityContextImpl EMPTY_CONTEXT = new SecurityContextImpl();

    @Override
    public int getOrder() {
        return -50;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .defaultIfEmpty(EMPTY_CONTEXT)
                .flatMap(ctx -> {
                    if (ctx.getAuthentication() instanceof JwtAuthenticationToken jwtAuth) {
                        var jwt = jwtAuth.getToken();
                        String sub = jwt.getSubject();
                        String role = extractRole(jwtAuth);
                        String merchantId = jwt.getClaimAsString("merchantId");

                        ServerHttpRequest.Builder builder = exchange.getRequest().mutate()
                                .header("X-User-Sub", sub != null ? sub : "")
                                .header("X-User-Role", role);
                        if (merchantId != null && !merchantId.isBlank()) {
                            builder.header("X-Merchant-Id", merchantId);
                        }
                        return chain.filter(exchange.mutate().request(builder.build()).build());
                    }
                    return chain.filter(exchange);
                });
    }

    private String extractRole(JwtAuthenticationToken auth) {
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .findFirst()
                .orElse("");
    }
}
