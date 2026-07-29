package com.creditcard.transaction.config;

import org.springframework.context.annotation.Configuration;

/**
 * Observability configuration. Micrometer Tracing (with Brave bridge) is fully
 * auto-configured by Spring Boot; this class is a placeholder for any future
 * custom instrumentation (e.g., gRPC metadata propagation interceptors).
 */
@Configuration
public class ObservabilityConfig {
}
