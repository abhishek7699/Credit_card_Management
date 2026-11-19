package com.creditcard.booking.tracing;

import brave.context.slf4j.MDCScopeDecorator;
import brave.propagation.CurrentTraceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Copies the active traceId and spanId into the SLF4J MDC whenever a span scope opens, so every
 * log line from a traced request or Kafka record carries them. Spring Boot does not register this decorator itself.
 */
@Configuration
public class MdcTracingConfig {

    @Bean
    CurrentTraceContext.ScopeDecorator mdcScopeDecorator() {
        return MDCScopeDecorator.get();
    }
}
