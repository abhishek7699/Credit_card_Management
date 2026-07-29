package com.creditcard.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MerchantScopeEnforcementFilterTest {

    private MerchantScopeEnforcementFilter filter;
    private WebFilterChain chain;
    private ObjectMapper realMapper;

    @BeforeEach
    void setUp() {
        realMapper = new ObjectMapper();
        filter = new MerchantScopeEnforcementFilter(realMapper);
        chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    void merchantRole_pathMatches_merchantIdMatches_passesThrough() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/reporting/merchants/merchant-001/summary")
                        .header("X-User-Role", "MERCHANT")
                        .header("X-Merchant-Id", "merchant-001")
                        .build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void merchantRole_pathMatches_merchantIdMismatch_returns403() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/reporting/merchants/merchant-002/summary")
                        .header("X-User-Role", "MERCHANT")
                        .header("X-Merchant-Id", "merchant-001")
                        .build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminRole_merchantIdMismatch_passesThrough() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/reporting/merchants/merchant-002/summary")
                        .header("X-User-Role", "ADMIN")
                        .build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
    }

    @Test
    void supportRole_passesThrough() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/reporting/merchants/merchant-002/summary")
                        .header("X-User-Role", "SUPPORT")
                        .build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
    }

    @Test
    void merchantRole_settlementPath_merchantIdMismatch_returns403() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/reporting/settlements/batch-001/merchants/merchant-002")
                        .header("X-User-Role", "MERCHANT")
                        .header("X-Merchant-Id", "merchant-001")
                        .build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void nonMerchantScopedPath_passesThrough() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/reporting/transactions")
                        .header("X-User-Role", "MERCHANT")
                        .header("X-Merchant-Id", "merchant-001")
                        .build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
    }

    @Test
    void merchantRole_reportingTransactionsNoMerchantId_passesThrough() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/reporting/merchants/summary")
                        .header("X-User-Role", "MERCHANT")
                        .header("X-Merchant-Id", "merchant-001")
                        .build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        // /reporting/merchants/summary has no {merchantId} segment — passes through
        verify(chain).filter(exchange);
    }

    @Test
    void writeForbidden_serializationFails_completesEmpty() throws Exception {
        ObjectMapper spyMapper = spy(realMapper);
        doThrow(new JsonProcessingException("test") {}).when(spyMapper).writeValueAsBytes(any());
        MerchantScopeEnforcementFilter spyFilter = new MerchantScopeEnforcementFilter(spyMapper);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/reporting/merchants/merchant-002/summary")
                        .header("X-User-Role", "MERCHANT")
                        .header("X-Merchant-Id", "merchant-001")
                        .build());

        StepVerifier.create(spyFilter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
