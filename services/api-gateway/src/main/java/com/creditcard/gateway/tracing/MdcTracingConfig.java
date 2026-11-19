package com.creditcard.gateway.tracing;

import brave.context.slf4j.MDCScopeDecorator;
import brave.propagation.CurrentTraceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Copies the active traceId and spanId into the SLF4J MDC whenever a span scope opens. The
 * gateway is reactive, so this runs on whichever thread Reactor restores the span onto (Spring
 * Boot's automatic context propagation carries the span across threads). Nothing here writes
 * MDC by hand: the scope decorator does it, so MDC always matches the current span.
 */
@Configuration
public class MdcTracingConfig {

    @Bean
    CurrentTraceContext.ScopeDecorator mdcScopeDecorator() {
        return MDCScopeDecorator.get();
    }
}
