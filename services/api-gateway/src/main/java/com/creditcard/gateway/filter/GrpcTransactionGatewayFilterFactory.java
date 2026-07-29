package com.creditcard.gateway.filter;

import com.creditcard.transaction.grpc.v1.GetTransactionRequest;
import com.creditcard.transaction.grpc.v1.GetTransactionResponse;
import com.creditcard.transaction.grpc.v1.InitiateTransactionRequest;
import com.creditcard.transaction.grpc.v1.InitiateTransactionResponse;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Component
public class GrpcTransactionGatewayFilterFactory
        extends AbstractGatewayFilterFactory<GrpcTransactionGatewayFilterFactory.Config> {

    private final TransactionServiceGrpc.TransactionServiceFutureStub stub;
    private final ObjectMapper objectMapper;

    public GrpcTransactionGatewayFilterFactory(
            TransactionServiceGrpc.TransactionServiceFutureStub stub,
            ObjectMapper objectMapper) {
        super(Config.class);
        this.stub = stub;
        this.objectMapper = objectMapper;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> switch (config.getOperation()) {
            case "INITIATE" -> handleInitiate(exchange);
            case "GET" -> handleGet(exchange);
            default -> chain.filter(exchange);
        };
    }

    private Mono<Void> handleInitiate(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    try {
                        TransactionRequestDto dto = objectMapper.readValue(bytes, TransactionRequestDto.class);
                        String merchantId = header(exchange, "X-Merchant-Id");
                        InitiateTransactionRequest req = InitiateTransactionRequest.newBuilder()
                                .setCardNumber(dto.cardNumber != null ? dto.cardNumber : "")
                                .setCardExpiry(dto.cardExpiry != null ? dto.cardExpiry : "")
                                .setCvv(dto.cvv != null ? dto.cvv : "")
                                .setCardholderName(dto.cardholderName != null ? dto.cardholderName : "")
                                .setAmount(dto.amount != null ? dto.amount : "")
                                .setCurrencyCode(dto.currencyCode != null ? dto.currencyCode : "")
                                .setMerchantId(merchantId != null ? merchantId : "")
                                .build();
                        return fromFuture(stub.initiateTransaction(req))
                                .flatMap(resp -> {
                                    Map<String, String> body = new LinkedHashMap<>();
                                    body.put("transactionId", resp.getTransactionId());
                                    body.put("status", resp.getStatus());
                                    return writeJson(exchange, HttpStatus.CREATED, body);
                                })
                                .onErrorResume(StatusRuntimeException.class,
                                        e -> handleGrpcError(exchange, e));
                    } catch (Exception e) {
                        return writeJson(exchange, HttpStatus.BAD_REQUEST,
                                Map.of("error", "Bad Request", "message", "Invalid request body"));
                    }
                })
                .switchIfEmpty(writeJson(exchange, HttpStatus.BAD_REQUEST,
                        Map.of("error", "Bad Request", "message", "Request body is required")));
    }

    private Mono<Void> handleGet(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        String transactionId = path.substring(path.lastIndexOf('/') + 1);
        String role = header(exchange, "X-User-Role");
        String merchantId = header(exchange, "X-Merchant-Id");

        GetTransactionRequest req = GetTransactionRequest.newBuilder()
                .setTransactionId(transactionId)
                .build();

        return fromFuture(stub.getTransaction(req))
                .flatMap(resp -> {
                    if ("MERCHANT".equals(role)
                            && !resp.getMerchantId().isEmpty()
                            && !resp.getMerchantId().equals(merchantId)) {
                        return writeJson(exchange, HttpStatus.FORBIDDEN,
                                Map.of("error", "Forbidden",
                                        "message", "You do not have access to this transaction"));
                    }
                    return writeJson(exchange, HttpStatus.OK, mapGetResponse(resp));
                })
                .onErrorResume(StatusRuntimeException.class, e -> handleGrpcError(exchange, e));
    }

    private Mono<Void> handleGrpcError(ServerWebExchange exchange, StatusRuntimeException e) {
        Status.Code code = e.getStatus().getCode();
        return switch (code) {
            case UNAVAILABLE, INTERNAL -> Mono.error(e);
            case NOT_FOUND -> writeJson(exchange, HttpStatus.NOT_FOUND,
                    Map.of("error", "Not Found", "message", "Transaction not found"));
            case INVALID_ARGUMENT -> writeJson(exchange, HttpStatus.BAD_REQUEST,
                    Map.of("error", "Bad Request",
                            "message", e.getStatus().getDescription() != null
                                    ? e.getStatus().getDescription() : "Invalid request"));
            case PERMISSION_DENIED -> writeJson(exchange, HttpStatus.FORBIDDEN,
                    Map.of("error", "Forbidden", "message", "Access denied"));
            default -> writeJson(exchange, HttpStatus.BAD_GATEWAY,
                    Map.of("error", "Bad Gateway", "message", "Upstream service error"));
        };
    }

    private Mono<Void> writeJson(ServerWebExchange exchange, HttpStatus status, Object body) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return exchange.getResponse().setComplete();
        }
    }

    private Map<String, Object> mapGetResponse(GetTransactionResponse resp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transactionId", resp.getTransactionId());
        m.put("maskedCardNumber", resp.getMaskedCardNumber());
        m.put("cardholderName", resp.getCardholderName());
        m.put("amount", resp.getAmount());
        m.put("currencyCode", resp.getCurrencyCode());
        m.put("status", resp.getStatus());
        m.put("createdAt", resp.getCreatedAt());
        m.put("updatedAt", resp.getUpdatedAt());
        return m;
    }

    private String header(ServerWebExchange exchange, String name) {
        return exchange.getRequest().getHeaders().getFirst(name);
    }

    private <T> Mono<T> fromFuture(ListenableFuture<T> future) {
        return Mono.create(sink ->
                future.addListener(() -> {
                    try {
                        sink.success(com.google.common.util.concurrent.Futures.getDone(future));
                    } catch (ExecutionException e) {
                        sink.error(e.getCause() != null ? e.getCause() : e);
                    } catch (Exception e) {
                        sink.error(e);
                    }
                }, MoreExecutors.directExecutor()));
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TransactionRequestDto {
        @JsonProperty("cardNumber") public String cardNumber;
        @JsonProperty("cardExpiry") public String cardExpiry;
        @JsonProperty("cvv") public String cvv;
        @JsonProperty("cardholderName") public String cardholderName;
        @JsonProperty("amount") public String amount;
        @JsonProperty("currencyCode") public String currencyCode;
    }

    // ── Config ────────────────────────────────────────────────────────────────

    public static class Config {
        private String operation;

        public String getOperation() { return operation; }
        public void setOperation(String operation) { this.operation = operation; }
    }
}
