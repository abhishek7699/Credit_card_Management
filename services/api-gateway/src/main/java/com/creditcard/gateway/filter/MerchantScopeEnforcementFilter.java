package com.creditcard.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class MerchantScopeEnforcementFilter implements WebFilter, Ordered {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final ObjectMapper mapper;

    // Patterns where {merchantId} path variable must match the JWT merchant ID for MERCHANT role
    private static final String PATTERN_MERCHANT_SUMMARY = "/api/v1/reporting/merchants/{merchantId}/summary";
    private static final String PATTERN_MERCHANT_SETTLEMENT = "/api/v1/reporting/settlements/{batchId}/merchants/{merchantId}";

    public MerchantScopeEnforcementFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int getOrder() {
        return -49;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String role = exchange.getRequest().getHeaders().getFirst("X-User-Role");
        if (!"MERCHANT".equals(role)) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        String merchantIdFromJwt = exchange.getRequest().getHeaders().getFirst("X-Merchant-Id");

        String extractedMerchantId = extractMerchantId(path);
        if (extractedMerchantId == null) {
            return chain.filter(exchange);
        }

        if (!extractedMerchantId.equals(merchantIdFromJwt)) {
            return writeForbidden(exchange);
        }

        return chain.filter(exchange);
    }

    private String extractMerchantId(String path) {
        if (MATCHER.match(PATTERN_MERCHANT_SUMMARY, path)) {
            return MATCHER.extractUriTemplateVariables(PATTERN_MERCHANT_SUMMARY, path).get("merchantId");
        }
        if (MATCHER.match(PATTERN_MERCHANT_SETTLEMENT, path)) {
            return MATCHER.extractUriTemplateVariables(PATTERN_MERCHANT_SETTLEMENT, path).get("merchantId");
        }
        return null;
    }

    private Mono<Void> writeForbidden(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] bytes = mapper.writeValueAsBytes(
                    Map.of("error", "Forbidden",
                            "message", "You do not have access to this merchant's data"));
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            return exchange.getResponse().setComplete();
        }
    }
}
