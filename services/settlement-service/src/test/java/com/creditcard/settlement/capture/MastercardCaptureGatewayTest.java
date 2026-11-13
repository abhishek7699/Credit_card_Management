package com.creditcard.settlement.capture;

import com.creditcard.mastercard.MastercardGatewayClient;
import com.creditcard.mastercard.MastercardGatewayConfig;
import com.creditcard.mastercard.TlsFixture;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/** Spec 014 phase 4: CAPTURE against a TLS WireMock server. No credentials, no calls to Mastercard. */
class MastercardCaptureGatewayTest {

    private static final String MERCHANT = "TESTMERCH";
    private static final String VERSION = "100";

    private WireMockServer server;
    private MastercardCaptureGateway gateway;
    private UUID txId;

    @BeforeEach
    void setUp() {
        server = TlsFixture.httpsServer();
        gateway = new MastercardCaptureGateway(new MastercardGatewayClient(new MastercardGatewayConfig(
                "https://localhost:" + server.httpsPort(), MERCHANT, "fixture-password", VERSION,
                Duration.ofSeconds(5), Duration.ofSeconds(5)), TlsFixture.trustingFixtureOnly()));
        txId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private String captureTransactionPath() {
        return "/api/rest/version/" + VERSION + "/merchant/" + MERCHANT + "/order/" + txId
                + "/transaction/" + UUID.nameUUIDFromBytes((txId + ":CAPTURE").getBytes());
    }

    private void putReturns(int status, String body) {
        server.stubFor(put(urlPathMatching(".*/transaction/.*")).willReturn(aResponse()
                .withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void getReturns(int status, String body) {
        server.stubFor(get(urlPathMatching(".*/transaction/.*")).willReturn(aResponse()
                .withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void successfulCaptureIsCaptured() {
        putReturns(200, "{\"result\":\"SUCCESS\",\"response\":{\"gatewayCode\":\"APPROVED\"}}");

        assertThat(gateway.capture(txId, BigDecimal.TEN, "USD")).isEqualTo(CaptureResult.CAPTURED);
        server.verify(putRequestedFor(urlPathEqualTo(captureTransactionPath())));
    }

    @Test
    void failedCaptureIsFailedAndNotRetriedHere() {
        putReturns(200, "{\"result\":\"FAILURE\",\"response\":{\"gatewayCode\":\"DECLINED\"}}");

        assertThat(gateway.capture(txId, BigDecimal.TEN, "USD")).isEqualTo(CaptureResult.FAILED);
        server.verify(1, putRequestedFor(urlPathMatching(".*/transaction/.*")));
    }

    @Test
    void unknownCaptureResolvedByLookupApprovedIsCaptured() {
        putReturns(500, "{}");
        getReturns(200, "{\"result\":\"SUCCESS\",\"response\":{\"gatewayCode\":\"APPROVED\"}}");

        assertThat(gateway.capture(txId, BigDecimal.TEN, "USD")).isEqualTo(CaptureResult.CAPTURED);
    }

    @Test
    void unknownCaptureResolvedByLookupDeclinedIsFailed() {
        putReturns(500, "{}");
        getReturns(200, "{\"result\":\"FAILURE\",\"response\":{\"gatewayCode\":\"DECLINED\"}}");

        assertThat(gateway.capture(txId, BigDecimal.TEN, "USD")).isEqualTo(CaptureResult.FAILED);
    }

    @Test
    void unknownCaptureWithNoCaptureFoundIsUnknown() {
        putReturns(500, "{}");
        getReturns(404, "{}");

        assertThat(gateway.capture(txId, BigDecimal.TEN, "USD")).isEqualTo(CaptureResult.UNKNOWN);
    }

    @Test
    void aRetriedCaptureReusesTheSameTransactionId() {
        putReturns(500, "{}");
        getReturns(404, "{}");
        assertThat(gateway.capture(txId, BigDecimal.TEN, "USD")).isEqualTo(CaptureResult.UNKNOWN);

        putReturns(200, "{\"result\":\"SUCCESS\",\"response\":{\"gatewayCode\":\"APPROVED\"}}");
        assertThat(gateway.capture(txId, BigDecimal.TEN, "USD")).isEqualTo(CaptureResult.CAPTURED);

        // Both attempts hit the same derived capture transaction, so the gateway sees one capture, not two.
        server.verify(2, putRequestedFor(urlPathEqualTo(captureTransactionPath())));
    }
}
