package com.creditcard.gateway.filter;

import com.creditcard.transaction.grpc.v1.GetTransactionResponse;
import com.creditcard.transaction.grpc.v1.InitiateTransactionResponse;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GrpcTransactionGatewayFilterFactoryTest {

    @Mock
    private TransactionServiceGrpc.TransactionServiceFutureStub stub;

    @Mock
    private GatewayFilterChain chain;

    private GrpcTransactionGatewayFilterFactory factory;
    private ObjectMapper objectMapper;

    private static final String VALID_INITIATE_BODY = """
            {"cardNumber":"4111111111111111","cardExpiry":"12/27","cvv":"123",\
            "cardholderName":"Jane Doe","amount":"99.99","currencyCode":"USD"}""";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        factory = new GrpcTransactionGatewayFilterFactory(stub, objectMapper);
        when(chain.filter(any())).thenReturn(reactor.core.publisher.Mono.empty());
    }

    private GatewayFilter initiateFilter() {
        GrpcTransactionGatewayFilterFactory.Config config = new GrpcTransactionGatewayFilterFactory.Config();
        config.setOperation("INITIATE");
        return factory.apply(config);
    }

    private GatewayFilter getFilter() {
        GrpcTransactionGatewayFilterFactory.Config config = new GrpcTransactionGatewayFilterFactory.Config();
        config.setOperation("GET");
        return factory.apply(config);
    }

    // ── INITIATE ──────────────────────────────────────────────────────────────

    @Test
    void initiate_validRequest_returns201WithTransactionId() {
        InitiateTransactionResponse grpcResp = InitiateTransactionResponse.newBuilder()
                .setTransactionId("txn-001")
                .setStatus("PENDING")
                .build();
        when(stub.initiateTransaction(any())).thenReturn(Futures.immediateFuture(grpcResp));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Role", "MERCHANT")
                        .header("X-Merchant-Id", "merchant-001")
                        .body(VALID_INITIATE_BODY));

        StepVerifier.create(initiateFilter().filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(stub).initiateTransaction(any());
        verify(chain, never()).filter(any());

        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("txn-001").contains("PENDING");
    }

    @Test
    void initiate_invalidJson_returns400() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Role", "MERCHANT")
                        .body("not-valid-json{{{"));

        StepVerifier.create(initiateFilter().filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(stub, never()).initiateTransaction(any());
    }

    // ── GET ───────────────────────────────────────────────────────────────────

    @Test
    void get_validRequest_returns200WithAllFields() {
        GetTransactionResponse grpcResp = GetTransactionResponse.newBuilder()
                .setTransactionId("txn-001")
                .setMaskedCardNumber("****-****-****-1111")
                .setCardholderName("Jane Doe")
                .setAmount("99.99")
                .setCurrencyCode("USD")
                .setStatus("AUTHORIZED")
                .setCreatedAt("2026-01-01T00:00:00Z")
                .setUpdatedAt("2026-01-01T00:01:00Z")
                .setMerchantId("merchant-001")
                .build();
        when(stub.getTransaction(any())).thenReturn(Futures.immediateFuture(grpcResp));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions/txn-001")
                        .header("X-User-Role", "MERCHANT")
                        .header("X-Merchant-Id", "merchant-001")
                        .build());

        StepVerifier.create(getFilter().filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("txn-001").contains("AUTHORIZED").contains("****-****-****-1111");
    }

    @Test
    void get_merchantMismatch_returns403() {
        GetTransactionResponse grpcResp = GetTransactionResponse.newBuilder()
                .setTransactionId("txn-001")
                .setMaskedCardNumber("****-****-****-9999")
                .setCardholderName("Other Merchant")
                .setAmount("50.00")
                .setCurrencyCode("USD")
                .setStatus("PENDING")
                .setMerchantId("merchant-002") // different from requesting merchant
                .build();
        when(stub.getTransaction(any())).thenReturn(Futures.immediateFuture(grpcResp));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions/txn-001")
                        .header("X-User-Role", "MERCHANT")
                        .header("X-Merchant-Id", "merchant-001")
                        .build());

        StepVerifier.create(getFilter().filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(any());
    }

    @Test
    void get_adminRole_merchantMismatchAllowed() {
        GetTransactionResponse grpcResp = GetTransactionResponse.newBuilder()
                .setTransactionId("txn-001")
                .setMerchantId("merchant-002")
                .setStatus("PENDING")
                .build();
        when(stub.getTransaction(any())).thenReturn(Futures.immediateFuture(grpcResp));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions/txn-001")
                        .header("X-User-Role", "ADMIN")
                        .build());

        StepVerifier.create(getFilter().filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── gRPC error mapping ────────────────────────────────────────────────────

    @Test
    void grpcUnavailable_propagatesAsMonoError() {
        when(stub.getTransaction(any())).thenReturn(
                Futures.immediateFailedFuture(Status.UNAVAILABLE.asRuntimeException()));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions/txn-001")
                        .header("X-User-Role", "MERCHANT")
                        .header("X-Merchant-Id", "merchant-001")
                        .build());

        StepVerifier.create(getFilter().filter(exchange, chain))
                .expectError(StatusRuntimeException.class)
                .verify();
    }

    @Test
    void grpcNotFound_returns404() {
        when(stub.getTransaction(any())).thenReturn(
                Futures.immediateFailedFuture(Status.NOT_FOUND.withDescription("Transaction not found").asRuntimeException()));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions/txn-missing")
                        .header("X-User-Role", "MERCHANT")
                        .header("X-Merchant-Id", "merchant-001")
                        .build());

        StepVerifier.create(getFilter().filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void grpcInvalidArgument_returns400() {
        when(stub.initiateTransaction(any())).thenReturn(
                Futures.immediateFailedFuture(Status.INVALID_ARGUMENT.withDescription("amount is invalid").asRuntimeException()));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Role", "MERCHANT")
                        .body(VALID_INITIATE_BODY));

        StepVerifier.create(initiateFilter().filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void grpcPermissionDenied_returns403() {
        when(stub.getTransaction(any())).thenReturn(
                Futures.immediateFailedFuture(Status.PERMISSION_DENIED.asRuntimeException()));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions/txn-001")
                        .header("X-User-Role", "MERCHANT")
                        .header("X-Merchant-Id", "merchant-001")
                        .build());

        StepVerifier.create(getFilter().filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void grpcInvalidArgument_nullDescription_returns400WithDefaultMessage() {
        when(stub.initiateTransaction(any())).thenReturn(
                Futures.immediateFailedFuture(Status.INVALID_ARGUMENT.asRuntimeException()));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Role", "MERCHANT")
                        .body(VALID_INITIATE_BODY));

        StepVerifier.create(initiateFilter().filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("Invalid request");
    }

    @Test
    void writeJson_serializationException_completesEmpty() throws Exception {
        ObjectMapper spyMapper = spy(objectMapper);
        doThrow(new JsonProcessingException("test") {}).when(spyMapper).writeValueAsBytes(any());
        factory = new GrpcTransactionGatewayFilterFactory(stub, spyMapper);

        InitiateTransactionResponse grpcResp = InitiateTransactionResponse.newBuilder()
                .setTransactionId("txn-err").setStatus("PENDING").build();
        when(stub.initiateTransaction(any())).thenReturn(Futures.immediateFuture(grpcResp));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Role", "MERCHANT")
                        .body(VALID_INITIATE_BODY));

        StepVerifier.create(initiateFilter().filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    void fromFuture_cancellationException_signalsError() {
        when(stub.getTransaction(any())).thenReturn(Futures.immediateCancelledFuture());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions/txn-001")
                        .header("X-User-Role", "MERCHANT")
                        .header("X-Merchant-Id", "merchant-001")
                        .build());

        StepVerifier.create(getFilter().filter(exchange, chain))
                .expectError()
                .verify();
    }

    // ── UNKNOWN operation ─────────────────────────────────────────────────────

    @Test
    void unknownOperation_passesThrough() {
        GrpcTransactionGatewayFilterFactory.Config config = new GrpcTransactionGatewayFilterFactory.Config();
        config.setOperation("UNKNOWN");
        GatewayFilter filter = factory.apply(config);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions/txn-001").build());

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        verify(chain).filter(exchange);
    }

    // ── null DTO fields (lines 63-68) ────────────────────────────────────────

    @Test
    void initiate_nullDtoFields_sendsEmptyStringsToGrpc() {
        InitiateTransactionResponse grpcResp = InitiateTransactionResponse.newBuilder()
                .setTransactionId("txn-null").setStatus("PENDING").build();
        when(stub.initiateTransaction(any())).thenReturn(Futures.immediateFuture(grpcResp));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Role", "MERCHANT")
                        // No X-Merchant-Id → merchantId already covered elsewhere; all DTO fields null via {}
                        .body("{}"));

        StepVerifier.create(initiateFilter().filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(stub).initiateTransaction(any());
    }

    // ── empty merchantId in GET response (line 102) ───────────────────────────

    @Test
    void get_emptyMerchantIdInResponse_returns200() {
        GetTransactionResponse grpcResp = GetTransactionResponse.newBuilder()
                .setTransactionId("txn-001")
                // merchantId defaults to "" (protobuf default)
                .setStatus("PENDING")
                .build();
        when(stub.getTransaction(any())).thenReturn(Futures.immediateFuture(grpcResp));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions/txn-001")
                        .header("X-User-Role", "MERCHANT")
                        .header("X-Merchant-Id", "merchant-001")
                        .build());

        StepVerifier.create(getFilter().filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── ExecutionException with null cause (line 165) ────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void fromFuture_executionExceptionNullCause_signalsException() throws Exception {
        ListenableFuture<GetTransactionResponse> mockFuture = mock(ListenableFuture.class);
        when(mockFuture.isDone()).thenReturn(true);
        when(mockFuture.get()).thenThrow(new ExecutionException("null-cause", null));
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                .when(mockFuture).addListener(any(Runnable.class), any(Executor.class));
        when(stub.getTransaction(any())).thenReturn(mockFuture);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/transactions/txn-001")
                        .header("X-User-Role", "MERCHANT")
                        .header("X-Merchant-Id", "merchant-001")
                        .build());

        StepVerifier.create(getFilter().filter(exchange, chain))
                .expectError(ExecutionException.class)
                .verify();
    }
}
