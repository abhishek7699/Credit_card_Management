package com.creditcard.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class AuditLoggingFilter implements WebFilter, Ordered {

    private Logger log = LoggerFactory.getLogger(AuditLoggingFilter.class);

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange).doAfterTerminate(() -> {
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            if (status != null && isAuditableStatus(status.value())) {
                String path = exchange.getRequest().getPath().value();
                String method = exchange.getRequest().getMethod().name();
                log.warn("Security event: {} {} returned {}", path, method, status.value());
            }
        });
    }

    private boolean isAuditableStatus(int code) {
        return code == 401 || code == 403 || code == 429 || code == 503;
    }
}
