package com.creditcard.mastercard.developers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mastercard.developer.oauth.OAuth;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Calls Mastercard Account Validation: POST {base}/account-validations. The OAuth 1.0a Authorization header
 * is produced by Mastercard's oauth1-signer over the exact JSON bytes that are sent (it hashes the body).
 *
 * <p>The card number and CVC live only in the {@link Request} and the request body string for the duration of
 * one call. Nothing here logs, and {@link Request#toString()} masks both.
 */
public final class AccountValidationClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final MastercardDevelopersConfig config;
    private final Duration requestTimeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public AccountValidationClient(MastercardDevelopersConfig config) {
        this(config, DEFAULT_TIMEOUT);
    }

    public AccountValidationClient(MastercardDevelopersConfig config, Duration requestTimeout) {
        this.config = config;
        this.requestTimeout = requestTimeout;
    }

    /** HTTP status and raw body, exactly as the sandbox returned them. */
    public record Result(int status, String body) {}

    /**
     * What Mastercard said. {@code approved} is true only when the issuer approved the account (network code
     * "00") AND the CVC matched AND the name was not a mismatch. {@code networkResponseCode} may be null when
     * Mastercard rejected the request itself (HTTP 400, e.g. an impossible card number).
     */
    public record Validation(String referenceId, boolean approved, String networkResponseCode) {}

    /** One validation request. Expiry is Mastercard's YYYY-MM. */
    public record Request(String referenceId, String pan, String expiryYearMonth, String cvc,
                          String firstName, String lastName) {

        /** From what a guest types: expiry as MM/YY and a single full name. */
        public static Request fromCard(String referenceId, String pan, String expiryMmYy, String cvc, String fullName) {
            String[] name = CardholderName.split(fullName);
            return new Request(referenceId, pan, toYearMonth(expiryMmYy), cvc, name[0], name[1]);
        }

        static String toYearMonth(String mmYy) {
            if (mmYy == null || !mmYy.matches("(0[1-9]|1[0-2])/\\d{2}")) {
                throw new IllegalArgumentException("expiry must be MM/YY");
            }
            return "20" + mmYy.substring(3, 5) + "-" + mmYy.substring(0, 2);
        }

        @Override
        public String toString() {
            return "Request{referenceId=" + referenceId + ", pan=****, cvc=****}";
        }
    }

    /**
     * Builds the request body. The sandbox routes the scenario on the referenceId prefix (e.g. "AVM_" = match),
     * and only reads the account number's first digit (5 = Mastercard).
     */
    static String buildBody(Request request, LocalDateTime now) {
        ObjectNode root = JSON.createObjectNode();
        root.put("referenceId", request.referenceId());
        ObjectNode details = root.putObject("accountDetails");
        ObjectNode account = details.putObject("account");
        account.put("type", "PAN");
        account.put("number", request.pan());
        account.put("expiry", request.expiryYearMonth());
        account.put("cvc", request.cvc());
        ObjectNode name = details.putObject("name");
        name.put("firstName", request.firstName());
        name.put("lastName", request.lastName());
        root.put("currency", "USD");
        root.put("transactionLocalDateTime", now.format(LOCAL_DATE_TIME));
        return root.toString();
    }

    HttpRequest buildRequest(String body) {
        URI uri = URI.create(config.baseUrl() + "/account-validations");
        String authorization = OAuth.getAuthorizationHeader(
                uri, "POST", body, StandardCharsets.UTF_8, config.consumerKey(), config.signingKey());
        return HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Partner-Id", config.partnerId())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private Result send(Request request, LocalDateTime now) throws IOException, InterruptedException {
        String body = buildBody(request, now);
        HttpResponse<String> response = http.send(buildRequest(body), HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), response.body());
    }

    /**
     * Validates the account. 200 is parsed into a {@link Validation}; 400 means Mastercard rejected the card
     * data itself, which is a "not approved" answer. Anything else (401, 5xx, timeout, network) throws
     * {@link AccountValidationUnavailableException} so the caller fails closed instead of guessing.
     */
    public Validation validate(Request request, LocalDateTime now) {
        Result result;
        try {
            result = send(request, now);
        } catch (IOException e) {
            throw new AccountValidationUnavailableException(
                    "Account Validation call failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AccountValidationUnavailableException("Account Validation call interrupted", e);
        }
        return interpret(request.referenceId(), result);
    }

    static Validation interpret(String referenceId, Result result) {
        if (result.status() == 400) {
            return new Validation(referenceId, false, null);
        }
        if (result.status() != 200) {
            throw new AccountValidationUnavailableException("Account Validation returned HTTP " + result.status());
        }
        try {
            JsonNode root = JSON.readTree(result.body());
            String code = root.path("networkResponseCode").asText("");
            String cvc = root.path("cvcValidationResults").path("status").asText("");
            String name = root.path("nameValidationResults").path("status").asText("");
            boolean approved = "00".equals(code) && "MATCHED".equals(cvc) && !"NOT_MATCHED".equals(name);
            return new Validation(referenceId, approved, code.isEmpty() ? null : code);
        } catch (IOException e) {
            throw new AccountValidationUnavailableException("Account Validation returned an unreadable body");
        }
    }

    /** Documented sandbox positive case: Mastercard PAN, CVC and name both MATCHED. Used by the manual smoke. */
    public Result validateSandboxPositive(LocalDateTime now) throws IOException, InterruptedException {
        String referenceId = "AVM_" + now.format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
        return send(new Request(referenceId, "5102589999999913", "2050-10", "123", "Jane", "Candy-Smith"), now);
    }
}
