package com.creditcard.authorization.processor;

import com.creditcard.authorization.domain.AuthorizationOutcome;
import com.creditcard.authorization.domain.AuthorizationRecord;
import com.creditcard.authorization.domain.ReasonCode;
import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import com.creditcard.authorization.repository.TimeoutMarkerRepository;
import com.creditcard.mastercard.MastercardGatewayClient;
import com.creditcard.mastercard.MastercardGatewayConfig;
import com.creditcard.mastercard.TlsFixture;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spec 014 phase 2 (T031–T036): the Mastercard processor against a TLS WireMock server that answers in the
 * gateway's documented format. No real credentials, and no calls to Mastercard.
 */
class MastercardGatewayProcessorTest {

    private static final String MERCHANT = "TESTMERCH";
    private static final String VERSION = "100";
    private static final String SESSION = "fixture-session-token";

    private WireMockServer server;
    private AuthorizationRecordRepository records;
    private TimeoutMarkerRepository markers;
    private MastercardGatewayProcessor processor;
    private UUID txId;

    @BeforeEach
    void setUp() {
        server = TlsFixture.httpsServer();
        records = mock(AuthorizationRecordRepository.class);
        markers = mock(TimeoutMarkerRepository.class);
        MastercardGatewayClient client = new MastercardGatewayClient(new MastercardGatewayConfig(
                "https://localhost:" + server.httpsPort(), MERCHANT, "fixture-password", VERSION,
                Duration.ofSeconds(5), Duration.ofSeconds(5)), TlsFixture.trustingFixtureOnly());
        processor = new MastercardGatewayProcessor(client, records, markers);
        txId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    private AuthorizeRequest request() {
        return new AuthorizeRequest(txId, new BigDecimal("42.00"), "USD", "merchant-1", SESSION);
    }

    private void putReturns(int status, String body) {
        server.stubFor(put(urlPathMatching(".*/transaction/.*")).willReturn(aResponse()
                .withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void getReturns(int status, String body) {
        server.stubFor(get(urlPathMatching(".*/transaction/.*")).willReturn(aResponse()
                .withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static String derived(UUID transactionId, String operation) {
        // Same derivation as the client: order = our transactionId; each operation's transaction ID is derived.
        return UUID.nameUUIDFromBytes((transactionId + ":" + operation).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String orderPath(String transaction) {
        return "/api/rest/version/" + VERSION + "/merchant/" + MERCHANT + "/order/" + txId
                + "/transaction/" + transaction;
    }

    private AuthorizationRecord authorizedRecord() {
        AuthorizationRecord record = new AuthorizationRecord();
        record.setTransactionId(txId);
        record.setRequestedAmount(new BigDecimal("42.00"));
        record.setCurrencyCode("USD");
        record.setOutcome(AuthorizationOutcome.AUTHORIZED);
        return record;
    }

    // ---- authorize ----------------------------------------------------------------------------

    @Test
    void approvalIsApprovedAndSendsOurTransactionIdAsTheOrderAndTransactionId() {
        putReturns(200, "{\"result\":\"SUCCESS\",\"response\":{\"gatewayCode\":\"APPROVED\"}}");

        ProcessorDecision decision = processor.authorize(request());

        assertThat(decision.getOutcome()).isEqualTo(ProcessorOutcome.APPROVED);
        server.verify(putRequestedFor(urlPathEqualTo(orderPath(derived(txId, "AUTHORIZE"))))
                .withRequestBody(equalToJson("{\"apiOperation\":\"AUTHORIZE\","
                        + "\"order\":{\"amount\":\"42.00\",\"currency\":\"USD\"},"
                        + "\"sourceOfFunds\":{\"session\":{\"id\":\"" + SESSION + "\"}}}")));
    }

    @Test
    void declineRecordsTheMastercardReasonNextToOurReasonCode() {
        putReturns(200, "{\"result\":\"FAILURE\",\"response\":{\"gatewayCode\":\"INSUFFICIENT_FUNDS\"}}");

        ProcessorDecision decision = processor.authorize(request());

        assertThat(decision.getOutcome()).isEqualTo(ProcessorOutcome.DECLINED);
        assertThat(decision.getReasonCode()).isEqualTo(ReasonCode.PROCESSOR_DECLINED.name());
        assertThat(decision.getProcessorCode()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void timeoutThenLookupApprovedIsApproved() {
        putReturns(500, "{}");
        getReturns(200, "{\"result\":\"SUCCESS\",\"response\":{\"gatewayCode\":\"APPROVED\"}}");

        assertThat(processor.authorize(request()).getOutcome()).isEqualTo(ProcessorOutcome.APPROVED);
    }

    @Test
    void timeoutThenLookupDeclinedIsDeclinedWithTheReason() {
        putReturns(500, "{}");
        getReturns(200, "{\"result\":\"FAILURE\",\"response\":{\"gatewayCode\":\"DECLINED\"}}");

        ProcessorDecision decision = processor.authorize(request());

        assertThat(decision.getOutcome()).isEqualTo(ProcessorOutcome.DECLINED);
        assertThat(decision.getProcessorCode()).isEqualTo("DECLINED");
    }

    @Test
    void timeoutThenLookupUnknownStaysUnknown() {
        putReturns(500, "{}");
        getReturns(500, "{}");

        assertThat(processor.authorize(request()).getOutcome()).isEqualTo(ProcessorOutcome.UNKNOWN);
    }

    @Test
    void duplicateIdentifierLooksUpInsteadOfResendingAuthorize() {
        putReturns(400, "{\"result\":\"FAILURE\",\"error\":{\"cause\":\"DUPLICATE_TRANSACTION_ID\"}}");
        getReturns(200, "{\"result\":\"SUCCESS\",\"response\":{\"gatewayCode\":\"APPROVED\"}}");

        assertThat(processor.authorize(request()).getOutcome()).isEqualTo(ProcessorOutcome.APPROVED);
        server.verify(1, putRequestedFor(urlPathMatching(".*/transaction/.*")));
    }

    @Test
    void malformedBodyIsResolvedByLookupAndNotFoundIsUnknown() {
        putReturns(200, "not json {");
        getReturns(404, "{}");

        assertThat(processor.authorize(request()).getOutcome()).isEqualTo(ProcessorOutcome.UNKNOWN);
    }

    @Test
    void aTimeoutMarkerSkipsTheAuthorizeWithoutCallingTheGateway() {
        when(markers.existsById(txId)).thenReturn(true);

        assertThat(processor.authorize(request()).getOutcome()).isEqualTo(ProcessorOutcome.TIMED_OUT_SKIPPED);
        server.verify(0, anyRequestedFor(anyUrl()));
    }

    // ---- releaseHold ---------------------------------------------------------------------------

    @Test
    void releaseWithNoAuthorizationIsNoAuthorizationAndSendsNoVoid() {
        when(records.findByTransactionId(txId)).thenReturn(List.of());

        assertThat(processor.releaseHold(txId, SESSION).getOutcome()).isEqualTo(ProcessorOutcome.NO_AUTHORIZATION);
        server.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void releaseWithOnlyADeclineIsANoOpAndSendsNoVoid() {
        AuthorizationRecord declined = authorizedRecord();
        declined.setOutcome(AuthorizationOutcome.DECLINED);
        when(records.findByTransactionId(txId)).thenReturn(List.of(declined));

        assertThat(processor.releaseHold(txId, SESSION).getOutcome()).isEqualTo(ProcessorOutcome.NO_OP);
        server.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void voidApprovedReleasesTheHoldAndMarksTheRecordReleased() {
        AuthorizationRecord record = authorizedRecord();
        when(records.findByTransactionId(txId)).thenReturn(List.of(record));
        putReturns(200, "{\"result\":\"SUCCESS\",\"response\":{\"gatewayCode\":\"APPROVED\"}}");

        assertThat(processor.releaseHold(txId, SESSION).getOutcome()).isEqualTo(ProcessorOutcome.RELEASED);
        assertThat(record.getOutcome()).isEqualTo(AuthorizationOutcome.RELEASED);
        server.verify(putRequestedFor(urlPathEqualTo(orderPath(derived(txId, "VOID"))))
                .withRequestBody(equalToJson("{\"apiOperation\":\"VOID\",\"transaction\":{\"targetTransactionId\":\""
                        + derived(txId, "AUTHORIZE") + "\"}}")));
    }

    @Test
    void voidRejectedKeepsTheRecordAuthorizedAndReportsTheReason() {
        AuthorizationRecord record = authorizedRecord();
        when(records.findByTransactionId(txId)).thenReturn(List.of(record));
        putReturns(200, "{\"result\":\"FAILURE\",\"response\":{\"gatewayCode\":\"ALREADY_CAPTURED\"}}");

        ProcessorDecision decision = processor.releaseHold(txId, SESSION);

        assertThat(decision.getOutcome()).isEqualTo(ProcessorOutcome.RELEASE_REJECTED);
        assertThat(decision.getReasonCode()).isEqualTo("ALREADY_CAPTURED");
        assertThat(record.getOutcome()).isEqualTo(AuthorizationOutcome.AUTHORIZED);
    }

    @Test
    void unknownVoidResolvedByTheVoidStatusApprovedIsReleased() {
        AuthorizationRecord record = authorizedRecord();
        when(records.findByTransactionId(txId)).thenReturn(List.of(record));
        putReturns(500, "{}");
        getReturns(200, "{\"result\":\"SUCCESS\",\"response\":{\"gatewayCode\":\"APPROVED\"}}");

        assertThat(processor.releaseHold(txId, SESSION).getOutcome()).isEqualTo(ProcessorOutcome.RELEASED);
        assertThat(record.getOutcome()).isEqualTo(AuthorizationOutcome.RELEASED);
    }

    @Test
    void unknownVoidWithNoVoidFoundIsRejectedAndTheHoldStays() {
        AuthorizationRecord record = authorizedRecord();
        when(records.findByTransactionId(txId)).thenReturn(List.of(record));
        putReturns(500, "{}");
        getReturns(404, "{}");

        ProcessorDecision decision = processor.releaseHold(txId, SESSION);

        assertThat(decision.getOutcome()).isEqualTo(ProcessorOutcome.RELEASE_REJECTED);
        assertThat(decision.getReasonCode()).isEqualTo("VOID_UNKNOWN");
        assertThat(record.getOutcome()).isEqualTo(AuthorizationOutcome.AUTHORIZED);
    }

    // ---- capture -------------------------------------------------------------------------------

    @Test
    void anApprovalAfterTheTimeoutMarkerIsVoidedAndPublishesNothing() {
        // Marker absent when the AUTHORIZE starts, present when its approval comes back: a release committed in between.
        when(markers.existsById(txId)).thenReturn(false, true);
        putReturns(200, "{\"result\":\"SUCCESS\",\"response\":{\"gatewayCode\":\"APPROVED\"}}");

        assertThat(processor.authorize(request()).getOutcome()).isEqualTo(ProcessorOutcome.TIMED_OUT_SKIPPED);
        server.verify(2, putRequestedFor(urlPathMatching(".*/transaction/.*")));
        server.verify(putRequestedFor(urlPathEqualTo(orderPath(derived(txId, "VOID"))))
                .withRequestBody(equalToJson("{\"apiOperation\":\"VOID\",\"transaction\":{\"targetTransactionId\":\""
                        + derived(txId, "AUTHORIZE") + "\"}}")));
    }

    @Test
    void captureIsNotAvailableUntilPhase4() {
        assertThatThrownBy(() -> processor.capture(txId, BigDecimal.ONE, "USD"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("phase 4");
    }
}
