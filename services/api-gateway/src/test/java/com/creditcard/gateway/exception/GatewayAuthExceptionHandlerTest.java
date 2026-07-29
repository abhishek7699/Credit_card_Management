package com.creditcard.gateway.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class GatewayAuthExceptionHandlerTest {

    private GatewayAuthExceptionHandler handler;
    private ObjectMapper realMapper;

    @BeforeEach
    void setUp() {
        realMapper = new ObjectMapper();
        handler = new GatewayAuthExceptionHandler(realMapper);
    }

    @Test
    void onAuthenticationException_returns401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions").build());

        StepVerifier.create(handler.commence(exchange, new BadCredentialsException("bad token")))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void onAuthenticationException_bodyContainsErrorField() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());

        handler.commence(exchange, new BadCredentialsException("expired")).block();

        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("\"error\"");
        assertThat(body).contains("Unauthorized");
    }

    @Test
    void onAccessDeniedException_returns403() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/reporting/merchants/summary").build());

        StepVerifier.create(handler.handle(exchange, new AccessDeniedException("forbidden")))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void onAccessDeniedException_bodyContainsErrorField() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());

        handler.handle(exchange, new AccessDeniedException("denied")).block();

        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("\"error\"");
        assertThat(body).contains("Forbidden");
    }

    @Test
    void writeJson_serializationFails_writesEmptyJson() throws Exception {
        ObjectMapper spyMapper = spy(realMapper);
        doThrow(new JsonProcessingException("test") {}).when(spyMapper).writeValueAsBytes(any());
        GatewayAuthExceptionHandler spyHandler = new GatewayAuthExceptionHandler(spyMapper);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions").build());

        StepVerifier.create(spyHandler.commence(exchange, new BadCredentialsException("bad")))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).isEqualTo("{}");
    }
}
