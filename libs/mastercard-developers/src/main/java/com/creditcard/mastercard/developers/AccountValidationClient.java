package com.creditcard.mastercard.developers;

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
 */
public final class AccountValidationClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final MastercardDevelopersConfig config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public AccountValidationClient(MastercardDevelopersConfig config) {
        this.config = config;
    }

    /** HTTP status and raw body, exactly as the sandbox returned them. */
    public record Result(int status, String body) {}

    /**
     * Builds the request body. The sandbox routes the scenario on the referenceId prefix (e.g. "AVM_" = match),
     * and only reads the account number's first digit (5 = Mastercard).
     */
    static String buildBody(String referenceId, String pan, String expiry, String cvc,
                            String firstName, String lastName, LocalDateTime now) {
        ObjectNode root = JSON.createObjectNode();
        root.put("referenceId", referenceId);
        ObjectNode details = root.putObject("accountDetails");
        ObjectNode account = details.putObject("account");
        account.put("type", "PAN");
        account.put("number", pan);
        account.put("expiry", expiry);
        account.put("cvc", cvc);
        ObjectNode name = details.putObject("name");
        name.put("firstName", firstName);
        name.put("lastName", lastName);
        root.put("currency", "USD");
        root.put("transactionLocalDateTime", now.format(LOCAL_DATE_TIME));
        return root.toString();
    }

    HttpRequest buildRequest(String body) {
        URI uri = URI.create(config.baseUrl() + "/account-validations");
        String authorization = OAuth.getAuthorizationHeader(
                uri, "POST", body, StandardCharsets.UTF_8, config.consumerKey(), config.signingKey());
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Partner-Id", config.partnerId())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    /** Documented sandbox positive case: Mastercard PAN, CVC and name both MATCHED. */
    public Result validateSandboxPositive(LocalDateTime now) throws IOException, InterruptedException {
        String referenceId = "AVM_" + now.format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
        String body = buildBody(referenceId, "5102589999999913", "2050-10", "123", "Jane", "Candy-Smith", now);
        HttpResponse<String> response = http.send(buildRequest(body), HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), response.body());
    }
}
