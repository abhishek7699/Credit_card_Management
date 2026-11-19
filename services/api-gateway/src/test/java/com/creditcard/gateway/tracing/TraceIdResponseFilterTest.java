package com.creditcard.gateway.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceIdResponseFilterTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static WebFilterChain passThrough() {
        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        return chain;
    }

    @Test
    void returnsTheActiveTraceIdInTheResponseHeader() {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn(TRACE_ID);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));

        new TraceIdResponseFilter(tracer).filter(exchange, passThrough()).block();
        exchange.getResponse().setComplete().block();

        assertThat(exchange.getResponse().getHeaders().getFirst(TraceIdResponseFilter.HEADER)).isEqualTo(TRACE_ID);
    }

    @Test
    void addsNoHeaderWhenNoSpanIsActive() {
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(null);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));

        new TraceIdResponseFilter(tracer).filter(exchange, passThrough()).block();
        exchange.getResponse().setComplete().block();

        assertThat(exchange.getResponse().getHeaders().containsKey(TraceIdResponseFilter.HEADER)).isFalse();
    }
}
