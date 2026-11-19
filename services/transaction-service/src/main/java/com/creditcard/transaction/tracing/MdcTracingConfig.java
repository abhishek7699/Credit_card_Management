package com.creditcard.transaction.tracing;

import brave.context.slf4j.MDCScopeDecorator;
import brave.propagation.CurrentTraceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Copies the active traceId and spanId into the SLF4J MDC while a span is current, so
 * every log line can print them (logback patterns use %X{traceId}). Spring Boot does not
 * register this decorator itself, so without this bean MDC stays empty.
 */
@Configuration
public class MdcTracingConfig {

    @Bean
    CurrentTraceContext.ScopeDecorator mdcScopeDecorator() {
        return MDCScopeDecorator.get();
    }
}
