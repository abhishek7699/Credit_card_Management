package com.creditcard.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AuditLoggingFilterTest {

    private AuditLoggingFilter filter;
    private Logger mockLogger;

    @BeforeEach
    void setUp() throws Exception {
        filter = new AuditLoggingFilter();
        mockLogger = Mockito.mock(Logger.class);
        Field logField = AuditLoggingFilter.class.getDeclaredField("log");
        logField.setAccessible(true);
        logField.set(filter, mockLogger);
    }

    private WebFilterChain chainWithStatus(HttpStatus status) {
        return exchange -> {
            exchange.getResponse().setStatusCode(status);
            return Mono.empty();
        };
    }

    @Test
    void status401_logsWarn() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions").build());

        StepVerifier.create(filter.filter(exchange, chainWithStatus(HttpStatus.UNAUTHORIZED)))
                .verifyComplete();

        verify(mockLogger, atLeastOnce()).warn(anyString(), anyString(), anyString(), any());
    }

    @Test
    void status403_logsWarn() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/reporting/merchants/summary").build());

        StepVerifier.create(filter.filter(exchange, chainWithStatus(HttpStatus.FORBIDDEN)))
                .verifyComplete();

        verify(mockLogger, atLeastOnce()).warn(anyString(), anyString(), anyString(), any());
    }

    @Test
    void status429_logsWarn() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions").build());

        StepVerifier.create(filter.filter(exchange, chainWithStatus(HttpStatus.TOO_MANY_REQUESTS)))
                .verifyComplete();

        verify(mockLogger, atLeastOnce()).warn(anyString(), anyString(), anyString(), any());
    }

    @Test
    void status503_logsWarn() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions").build());

        StepVerifier.create(filter.filter(exchange, chainWithStatus(HttpStatus.SERVICE_UNAVAILABLE)))
                .verifyComplete();

        verify(mockLogger, atLeastOnce()).warn(anyString(), anyString(), anyString(), any());
    }

    @Test
    void status200_noWarnLogged() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions").build());

        StepVerifier.create(filter.filter(exchange, chainWithStatus(HttpStatus.OK)))
                .verifyComplete();

        verify(mockLogger, never()).warn(anyString(), anyString(), anyString(), any());
    }

    @Test
    void nullStatus_noWarnLogged() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions").build());
        // Chain completes without setting any status → getStatusCode() returns null
        WebFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(mockLogger, never()).warn(anyString(), anyString(), anyString(), any());
    }

    @Test
    void logMessageDoesNotContainPan() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions")
                        .header("X-User-Sub", "4111111111111111") // PAN-like value in header
                        .build());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        StepVerifier.create(filter.filter(exchange, chainWithStatus(HttpStatus.UNAUTHORIZED)))
                .verifyComplete();

        // Log call uses path and method, not the sub header value directly
        verify(mockLogger, atLeastOnce()).warn(anyString(), anyString(), anyString(), captor.capture());
        // The captured args should be path, method, status — none should be the raw sub header
        captor.getAllValues().forEach(val -> {
            if (val instanceof String s) {
                assertThat(s).doesNotMatch("\\b4111111111111111\\b");
            }
        });
    }
}
