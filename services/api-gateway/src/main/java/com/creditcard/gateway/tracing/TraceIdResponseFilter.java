package com.creditcard.gateway.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Returns the request's trace id in an X-Trace-Id response header, so a client can find the
 * whole request in Zipkin. The id is captured from the active span up front and written
 * just before the response commits. Runs right after Spring's observation filter, which is
 * what creates the span (or continues the incoming traceparent).
 */
@Component
public class TraceIdResponseFilter implements WebFilter, Ordered {

    static final String HEADER = "X-Trace-Id";

    private final Tracer tracer;

    public TraceIdResponseFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Span span = tracer.currentSpan();
        if (span != null) {
            String traceId = span.context().traceId();
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().set(HEADER, traceId);
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }
}
