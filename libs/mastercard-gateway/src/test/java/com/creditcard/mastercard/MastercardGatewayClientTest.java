package com.creditcard.mastercard;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/** The response mapping and the request shape, against a TLS WireMock server (spec 014 phase 2, T031). */
class MastercardGatewayClientTest {

    private static final String MERCHANT = "TESTMERCH";
    private static final String PASSWORD = "pw-secret-value";
    private static final String VERSION = "100";
    private static final String SESSION = "SESSION-ABCD1234";

    private WireMockServer server;
    private UUID txId;

    @BeforeEach
    void startServer() {
        server = TlsFixture.httpsServer();
        txId = UUID.randomUUID();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private MastercardGatewayClient client() {
        return client(Duration.ofSeconds(5));
    }

    private MastercardGatewayClient client(Duration readTimeout) {
        MastercardGatewayConfig config = new MastercardGatewayConfig(
                "https://localhost:" + server.httpsPort(), MERCHANT, PASSWORD, VERSION,
                Duration.ofSeconds(5), readTimeout);
        return new MastercardGatewayClient(config, TlsFixture.trustingFixtureOnly());
    }

    private String orderPath(UUID order, UUID transaction) {
        return "/api/rest/version/" + VERSION + "/merchant/" + MERCHANT + "/order/" + order
                + "/transaction/" + transaction;
    }

    private void stubPut(String body) {
        server.stubFor(put(urlPathMatching(".*/transaction/.*")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void stubPutStatus(int status, String body) {
        server.stubFor(put(urlPathMatching(".*/transaction/.*")).willReturn(aResponse()
                .withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void stubGet(int status, String body) {
        server.stubFor(get(urlPathMatching(".*/transaction/.*")).willReturn(aResponse()
                .withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    // ---- AUTHORIZE --------------------------------------------------------------------------

    @Test
    void authorizeApprovedSendsOurIdsSessionAndAmountWithBasicAuth() {
        stubPut("{\"result\":\"SUCCESS\",\"response\":{\"gatewayCode\":\"APPROVED\"}}");

        GatewayResult result = client().authorize(txId, new BigDecimal("12.5"), "USD", SESSION);

        assertThat(result.outcome()).isEqualTo(GatewayOutcome.APPROVED);
        assertThat(result.gatewayCode()).isEqualTo("APPROVED");
        server.verify(putRequestedFor(urlPathEqualTo(orderPath(txId, MastercardGatewayClient.authorizeTransactionId(txId))))
                .withHeader("Authorization", equalTo("Basic " + Base64.getEncoder()
                        .encodeToString(("merchant." + MERCHANT + ":" + PASSWORD).getBytes())))
                .withRequestBody(equalToJson("{\"apiOperation\":\"AUTHORIZE\","
                        + "\"order\":{\"amount\":\"12.50\",\"currency\":\"USD\"},"
                        + "\"sourceOfFunds\":{\"session\":{\"id\":\"" + SESSION + "\"}}}")));
    }

    @Test
    void authorizeDeclinedRecordsTheGatewayReason() {
        stubPut("{\"result\":\"FAILURE\",\"response\":{\"gatewayCode\":\"DECLINED\"}}");

        GatewayResult result = client().authorize(txId, BigDecimal.TEN, "USD", SESSION);

        assertThat(result.outcome()).isEqualTo(GatewayOutcome.DECLINED);
        assertThat(result.gatewayCode()).isEqualTo("DECLINED");
    }

    @Test
    void successWithADeclineCodeIsADecline() {
        stubPut("{\"result\":\"SUCCESS\",\"response\":{\"gatewayCode\":\"INSUFFICIENT_FUNDS\"}}");

        GatewayResult result = client().authorize(txId, BigDecimal.TEN, "USD", SESSION);

        assertThat(result.outcome()).isEqualTo(GatewayOutcome.DECLINED);
        assertThat(result.gatewayCode()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void failureWithoutAGatewayCodeIsADeclineWithAGenericReason() {
        stubPut("{\"result\":\"FAILURE\"}");

        GatewayResult result = client().authorize(txId, BigDecimal.TEN, "USD", SESSION);

        assertThat(result.outcome()).isEqualTo(GatewayOutcome.DECLINED);
        assertThat(result.gatewayCode()).isEqualTo("FAILURE");
    }

    @Test
    void successWithoutAGatewayCodeIsUnknown() {
        stubPut("{\"result\":\"SUCCESS\"}");

        assertThat(client().authorize(txId, BigDecimal.TEN, "USD", SESSION).outcome())
                .isEqualTo(GatewayOutcome.UNKNOWN);
    }

    @Test
    void pendingResultIsUnknown() {
        stubPut("{\"result\":\"PENDING\"}");

        assertThat(client().authorize(txId, BigDecimal.TEN, "USD", SESSION).outcome())
                .isEqualTo(GatewayOutcome.UNKNOWN);
    }

    @Test
    void serverErrorIsUnknownNeverDeclined() {
        stubPutStatus(500, "{\"result\":\"FAILURE\",\"response\":{\"gatewayCode\":\"DECLINED\"}}");

        assertThat(client().authorize(txId, BigDecimal.TEN, "USD", SESSION).outcome())
                .isEqualTo(GatewayOutcome.UNKNOWN);
    }

    @Test
    void duplicateIdentifierIsUnknownSoTheCallerLooksItUp() {
        stubPutStatus(400, "{\"result\":\"FAILURE\",\"error\":{\"cause\":\"DUPLICATE_TRANSACTION_ID\"}}");

        assertThat(client().authorize(txId, BigDecimal.TEN, "USD", SESSION).outcome())
                .isEqualTo(GatewayOutcome.UNKNOWN);
    }

    @Test
    void malformedAndEmptyBodiesAreUnknown() {
        stubPut("not json {");
        assertThat(client().authorize(txId, BigDecimal.TEN, "USD", SESSION).outcome())
                .isEqualTo(GatewayOutcome.UNKNOWN);

        stubPut("");
        assertThat(client().authorize(txId, BigDecimal.TEN, "USD", SESSION).outcome())
                .isEqualTo(GatewayOutcome.UNKNOWN);
    }

    @Test
    void aTimeoutIsUnknown() {
        server.stubFor(put(urlPathMatching(".*/transaction/.*")).willReturn(aResponse()
                .withStatus(200).withFixedDelay(2000).withBody("{\"result\":\"SUCCESS\"}")));

        GatewayResult result = client(Duration.ofMillis(200)).authorize(txId, BigDecimal.TEN, "USD", SESSION);

        assertThat(result.outcome()).isEqualTo(GatewayOutcome.UNKNOWN);
        assertThat(result.gatewayCode()).isNull();
    }

    @Test
    void aConnectionFailureIsUnknown() {
        MastercardGatewayConfig config = new MastercardGatewayConfig(
                "https://localhost:1", MERCHANT, PASSWORD, VERSION, Duration.ofSeconds(2), Duration.ofSeconds(2));
        GatewayResult result = new MastercardGatewayClient(config, TlsFixture.trustingFixtureOnly())
                .authorize(txId, BigDecimal.TEN, "USD", SESSION);

        assertThat(result.outcome()).isEqualTo(GatewayOutcome.UNKNOWN);
    }

    // ---- RETRIEVE (status lookup) -----------------------------------------------------------

    @Test
    void retrieveApprovedAndDeclinedAndNotFound() {
        stubGet(200, "{\"result\":\"SUCCESS\",\"response\":{\"gatewayCode\":\"APPROVED\"}}");
        assertThat(client().retrieve(txId).outcome()).isEqualTo(GatewayOutcome.APPROVED);

        stubGet(200, "{\"result\":\"FAILURE\",\"response\":{\"gatewayCode\":\"DECLINED\"}}");
        assertThat(client().retrieve(txId).outcome()).isEqualTo(GatewayOutcome.DECLINED);

        stubGet(404, "{\"result\":\"FAILURE\"}");
        assertThat(client().retrieve(txId).outcome()).isEqualTo(GatewayOutcome.UNKNOWN);

        server.verify(getRequestedFor(urlPathEqualTo(orderPath(txId, MastercardGatewayClient.authorizeTransactionId(txId)))));
    }

    // ---- VOID -------------------------------------------------------------------------------

    @Test
    void voidApprovedUsesADeterministicVoidTransactionIdAndTargetsOurAuthorization() {
        stubPut("{\"result\":\"SUCCESS\",\"response\":{\"gatewayCode\":\"APPROVED\"}}");
        UUID voidId = MastercardGatewayClient.voidTransactionId(txId);

        GatewayResult result = client().voidAuthorization(txId);

        assertThat(result.outcome()).isEqualTo(GatewayOutcome.VOIDED);
        assertThat(voidId).isNotEqualTo(txId);
        assertThat(MastercardGatewayClient.voidTransactionId(txId)).isEqualTo(voidId);
        server.verify(putRequestedFor(urlPathEqualTo(orderPath(txId, voidId)))
                .withRequestBody(equalToJson("{\"apiOperation\":\"VOID\",\"transaction\":{\"targetTransactionId\":\""
                        + MastercardGatewayClient.authorizeTransactionId(txId) + "\"}}")));
    }

    @Test
    void voidRefusedIsVoidRejectedWithTheReason() {
        stubPut("{\"result\":\"FAILURE\",\"response\":{\"gatewayCode\":\"ALREADY_CAPTURED\"}}");

        GatewayResult result = client().voidAuthorization(txId);

        assertThat(result.outcome()).isEqualTo(GatewayOutcome.VOID_REJECTED);
        assertThat(result.gatewayCode()).isEqualTo("ALREADY_CAPTURED");
    }

    @Test
    void voidSuccessWithAnotherCodeIsVoidRejected() {
        stubPut("{\"result\":\"SUCCESS\",\"response\":{\"gatewayCode\":\"NOT_VOIDABLE\"}}");

        assertThat(client().voidAuthorization(txId).outcome()).isEqualTo(GatewayOutcome.VOID_REJECTED);
    }

    @Test
    void voidFailureWithoutACodeIsVoidRejectedWithAGenericReason() {
        stubPut("{\"result\":\"FAILURE\"}");

        GatewayResult result = client().voidAuthorization(txId);

        assertThat(result.outcome()).isEqualTo(GatewayOutcome.VOID_REJECTED);
        assertThat(result.gatewayCode()).isEqualTo("FAILURE");
    }

    @Test
    void retrieveVoidLooksUpTheVoidTransactionNotTheAuthorization() {
        stubGet(200, "{\"result\":\"SUCCESS\",\"response\":{\"gatewayCode\":\"APPROVED\"}}");

        assertThat(client().retrieveVoid(txId).outcome()).isEqualTo(GatewayOutcome.APPROVED);
        server.verify(getRequestedFor(urlPathEqualTo(
                orderPath(txId, MastercardGatewayClient.voidTransactionId(txId)))));
    }

    @Test
    void captureSendsCaptureWithDerivedIdsAndTargetsTheAuthorization() {
        stubPut("{\"result\":\"SUCCESS\",\"response\":{\"gatewayCode\":\"APPROVED\"}}");
        UUID captureId = MastercardGatewayClient.captureTransactionId(txId);

        GatewayResult result = client().capture(txId, new BigDecimal("12.5"), "USD");

        assertThat(result.outcome()).isEqualTo(GatewayOutcome.APPROVED);
        assertThat(captureId).isNotEqualTo(txId);
        server.verify(putRequestedFor(urlPathEqualTo(orderPath(txId, captureId)))
                .withRequestBody(equalToJson("{\"apiOperation\":\"CAPTURE\",\"transaction\":{\"amount\":\"12.50\","
                        + "\"currency\":\"USD\",\"targetTransactionId\":\""
                        + MastercardGatewayClient.authorizeTransactionId(txId) + "\"}}")));
    }

    @Test
    void captureDeclinedIsDeclined() {
        stubPut("{\"result\":\"FAILURE\",\"response\":{\"gatewayCode\":\"DECLINED\"}}");

        assertThat(client().capture(txId, BigDecimal.TEN, "USD").outcome()).isEqualTo(GatewayOutcome.DECLINED);
    }

    @Test
    void retrieveCaptureLooksUpTheCaptureTransaction() {
        stubGet(200, "{\"result\":\"SUCCESS\",\"response\":{\"gatewayCode\":\"APPROVED\"}}");

        assertThat(client().retrieveCapture(txId).outcome()).isEqualTo(GatewayOutcome.APPROVED);
        server.verify(getRequestedFor(urlPathEqualTo(
                orderPath(txId, MastercardGatewayClient.captureTransactionId(txId)))));
    }

    @Test
    void voidServerErrorIsUnknown() {
        stubPutStatus(503, "{}");

        assertThat(client().voidAuthorization(txId).outcome()).isEqualTo(GatewayOutcome.UNKNOWN);
    }
}
