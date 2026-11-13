package com.creditcard.mastercard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.net.ssl.SSLContext;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST client for Mastercard Gateway, API version from config (spec 014, contracts/mastercard-gateway.md).
 *
 * <p>Identifiers: the order ID and the authorization's transaction ID are our {@code transactionId}.
 * A VOID is its own transaction on the same order, so it gets a deterministic ID derived from ours
 * ({@link #voidTransactionId}). A retried VOID therefore reuses the same ID.
 *
 * <p>Every result is one of {@link GatewayOutcome}. Timeouts, connection errors, 5xx, 404, duplicate
 * identifiers and unclear responses all become UNKNOWN. The caller then looks the transaction up. Nothing
 * here decides DECLINED from an unknown result.
 *
 * <p>Logging: the operation, our transaction ID, the outcome and the gateway code only. Never a request
 * or response body, the session ID, the password, or the Authorization header.
 */
public final class MastercardGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(MastercardGatewayClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MastercardGatewayConfig config;
    private final RestClient rest;

    /**
     * @param sslContext trust settings. Production passes {@code SSLContext.getDefault()}, which verifies
     *                   certificates. Tests pass a context that trusts only their own fixture certificate.
     */
    public MastercardGatewayClient(MastercardGatewayConfig config, SSLContext sslContext) {
        this.config = config;
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(config.getConnectTimeout())
                .sslContext(sslContext)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(config.getReadTimeout());
        this.rest = RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .requestFactory(factory)
                .defaultHeaders(h -> h.setBasicAuth("merchant." + config.getMerchantId(), config.getApiPassword()))
                .build();
    }

    /** AUTHORIZE with a payment session (Hosted Session). The backend never sends card data. */
    public GatewayResult authorize(UUID transactionId, BigDecimal amount, String currency, String sessionId) {
        Map<String, Object> body = Map.of(
                "apiOperation", "AUTHORIZE",
                "order", Map.of("amount", amount.setScale(2).toPlainString(), "currency", currency),
                "sourceOfFunds", Map.of("session", Map.of("id", sessionId)));
        return send(Operation.AUTHORIZE, transactionId, HttpMethod.PUT,
                orderPath(transactionId, authorizeTransactionId(transactionId)), body);
    }

    /** Retrieve the authorization's status. Used after UNKNOWN. */
    public GatewayResult retrieve(UUID transactionId) {
        return send(Operation.RETRIEVE, transactionId, HttpMethod.GET,
                orderPath(transactionId, authorizeTransactionId(transactionId)), null);
    }

    /** VOID the authorization. Only valid before capture. */
    public GatewayResult voidAuthorization(UUID transactionId) {
        Map<String, Object> body = Map.of(
                "apiOperation", "VOID",
                "transaction", Map.of("targetTransactionId", authorizeTransactionId(transactionId).toString()));
        return send(Operation.VOID, transactionId, HttpMethod.PUT,
                orderPath(transactionId, voidTransactionId(transactionId)), body);
    }

    /** CAPTURE the authorized amount (settlement, spec 014 phase 4). The transaction ID is derived from ours, so a retry reuses it. */
    public GatewayResult capture(UUID transactionId, BigDecimal amount, String currency) {
        Map<String, Object> body = Map.of(
                "apiOperation", "CAPTURE",
                "transaction", Map.of(
                        "amount", amount.setScale(2).toPlainString(),
                        "currency", currency,
                        "targetTransactionId", authorizeTransactionId(transactionId).toString()));
        return send(Operation.CAPTURE, transactionId, HttpMethod.PUT,
                orderPath(transactionId, captureTransactionId(transactionId)), body);
    }

    /** Retrieve the CAPTURE transaction's status. Used when a CAPTURE result is UNKNOWN. */
    public GatewayResult retrieveCapture(UUID transactionId) {
        return send(Operation.RETRIEVE, transactionId, HttpMethod.GET,
                orderPath(transactionId, captureTransactionId(transactionId)), null);
    }

    /** Deterministic ID for the CAPTURE transaction: transactionId + ":CAPTURE". */
    public static UUID captureTransactionId(UUID transactionId) {
        return UUID.nameUUIDFromBytes((transactionId + ":CAPTURE").getBytes(StandardCharsets.UTF_8));
    }

    /** Retrieve the VOID transaction's status. Used when a VOID result is UNKNOWN. */
    public GatewayResult retrieveVoid(UUID transactionId) {
        return send(Operation.RETRIEVE, transactionId, HttpMethod.GET,
                orderPath(transactionId, voidTransactionId(transactionId)), null);
    }

    /**
     * Gateway transaction ID for the AUTHORIZE: derived from our transactionId, so the order ID stays ours.
     * Same input, same ID, so a retried AUTHORIZE or lookup targets the same transaction.
     */
    static UUID authorizeTransactionId(UUID transactionId) {
        return UUID.nameUUIDFromBytes((transactionId + ":AUTHORIZE").getBytes(StandardCharsets.UTF_8));
    }

    /** Deterministic ID for the VOID transaction: same input, same ID, so a retried VOID is idempotent. */
    static UUID voidTransactionId(UUID transactionId) {
        return UUID.nameUUIDFromBytes((transactionId + ":VOID").getBytes(StandardCharsets.UTF_8));
    }

    private String orderPath(UUID orderId, UUID transactionId) {
        return "/api/rest/version/" + config.getApiVersion()
                + "/merchant/" + config.getMerchantId()
                + "/order/" + orderId
                + "/transaction/" + transactionId;
    }

    private GatewayResult send(Operation operation, UUID transactionId, HttpMethod method, String path, Object body) {
        GatewayResult result;
        try {
            result = rest.method(method)
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body == null ? "" : body)
                    .exchange((request, response) -> classify(operation,
                            response.getStatusCode().value(),
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (RestClientException e) {
            // Timeout, connection refused or reset, or a body that could not be read: the result is unknown.
            result = new GatewayResult(GatewayOutcome.UNKNOWN, null);
        }
        log.info("Mastercard {} txId={} outcome={} gatewayCode={}",
                operation, transactionId, result.outcome(), result.gatewayCode());
        return result;
    }

    /** The response mapping (contracts/mastercard-gateway.md). Package-private for direct tests. */
    static GatewayResult classify(Operation operation, int status, String text) {
        if (status >= 500 || status == 404) {
            return unknown();
        }
        if (text.contains("DUPLICATE")) {
            // A duplicate identifier means an earlier request already reached the gateway.
            // The caller looks the transaction up and never re-sends AUTHORIZE.
            return unknown();
        }
        Optional<JsonNode> node = parse(text);
        if (node.isEmpty()) {
            return unknown();
        }
        String result = node.get().path("result").asText("");
        String gatewayCode = node.get().path("response").path("gatewayCode").asText(null);

        if ("SUCCESS".equals(result) && gatewayCode != null) {
            if ("APPROVED".equals(gatewayCode)) {
                return operation == Operation.VOID
                        ? new GatewayResult(GatewayOutcome.VOIDED, gatewayCode)
                        : new GatewayResult(GatewayOutcome.APPROVED, gatewayCode);
            }
            return operation == Operation.VOID
                    ? new GatewayResult(GatewayOutcome.VOID_REJECTED, gatewayCode)
                    : new GatewayResult(GatewayOutcome.DECLINED, gatewayCode);
        }
        if ("FAILURE".equals(result)) {
            String code = gatewayCode == null ? "FAILURE" : gatewayCode;
            return operation == Operation.VOID
                    ? new GatewayResult(GatewayOutcome.VOID_REJECTED, code)
                    : new GatewayResult(GatewayOutcome.DECLINED, code);
        }
        return unknown();
    }

    private static Optional<JsonNode> parse(String text) {
        try {
            return Optional.ofNullable(JSON.readTree(text)).filter(n -> !n.isMissingNode());
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static GatewayResult unknown() {
        return new GatewayResult(GatewayOutcome.UNKNOWN, null);
    }

    enum Operation {
        AUTHORIZE, CAPTURE, RETRIEVE, VOID
    }
}
