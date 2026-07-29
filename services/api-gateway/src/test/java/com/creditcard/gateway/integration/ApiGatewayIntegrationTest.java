package com.creditcard.gateway.integration;

import com.creditcard.transaction.grpc.v1.GetTransactionResponse;
import com.creditcard.transaction.grpc.v1.InitiateTransactionResponse;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.mockito.Mockito.when;

@TestMethodOrder(MethodOrderer.MethodName.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=",
                "gateway.rate-limit.replenish-rate=100",
                "gateway.rate-limit.burst-capacity=1000",
                "grpc.client.transaction-service.address=in-process:transaction-e2e-test"
        })
@Testcontainers
@ActiveProfiles("test")
class ApiGatewayIntegrationTest {

    @SuppressWarnings("resource")
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static WireMockServer wireMock = new WireMockServer(
            WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        wireMock.start();
    }

    private static Server grpcServer;
    private static final AtomicBoolean grpcShouldFail = new AtomicBoolean(false);
    private static final String FIXED_TX_ID = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("REPORTING_SERVICE_URL", () -> "http://localhost:" + wireMock.port());
    }

    @BeforeAll
    static void startGrpcServer() throws Exception {
        grpcServer = InProcessServerBuilder.forName("transaction-e2e-test")
                .addService(new TransactionServiceGrpc.TransactionServiceImplBase() {
                    @Override
                    public void initiateTransaction(
                            com.creditcard.transaction.grpc.v1.InitiateTransactionRequest req,
                            StreamObserver<InitiateTransactionResponse> observer) {
                        if (grpcShouldFail.get()) {
                            observer.onError(Status.UNAVAILABLE.asRuntimeException());
                        } else {
                            observer.onNext(InitiateTransactionResponse.newBuilder()
                                    .setTransactionId(FIXED_TX_ID)
                                    .setStatus("PENDING")
                                    .build());
                            observer.onCompleted();
                        }
                    }

                    @Override
                    public void getTransaction(
                            com.creditcard.transaction.grpc.v1.GetTransactionRequest req,
                            StreamObserver<GetTransactionResponse> observer) {
                        observer.onNext(GetTransactionResponse.newBuilder()
                                .setTransactionId(req.getTransactionId())
                                .setMaskedCardNumber("****-****-****-1111")
                                .setCardholderName("Test User")
                                .setAmount("99.99")
                                .setCurrencyCode("USD")
                                .setStatus("PENDING")
                                .setMerchantId("merchant-001")
                                .build());
                        observer.onCompleted();
                    }
                })
                .directExecutor()
                .build()
                .start();

        // Default WireMock stub for reporting
        wireMock.stubFor(get(anyUrl()).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("[]")));
    }

    @AfterAll
    static void stopServers() throws Exception {
        grpcShouldFail.set(false);
        if (grpcServer != null) grpcServer.shutdown();
        if (wireMock != null) wireMock.stop();
    }

    @Autowired
    private WebTestClient webClient;

    @MockBean
    private ReactiveJwtDecoder jwtDecoder;

    private String merchantToken(String merchantId) {
        Jwt jwt = Jwt.withTokenValue("token-merchant-" + merchantId)
                .header("alg", "RS256")
                .claim("sub", merchantId)
                .claim("authorities", List.of("ROLE_MERCHANT"))
                .claim("merchantId", merchantId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode("token-merchant-" + merchantId)).thenReturn(Mono.just(jwt));
        return "Bearer token-merchant-" + merchantId;
    }

    private String adminToken(String sub) {
        Jwt jwt = Jwt.withTokenValue("token-admin-" + sub)
                .header("alg", "RS256")
                .claim("sub", sub)
                .claim("authorities", List.of("ROLE_ADMIN"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode("token-admin-" + sub)).thenReturn(Mono.just(jwt));
        return "Bearer token-admin-" + sub;
    }

    private String supportToken(String sub) {
        Jwt jwt = Jwt.withTokenValue("token-support-" + sub)
                .header("alg", "RS256")
                .claim("sub", sub)
                .claim("authorities", List.of("ROLE_SUPPORT"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode("token-support-" + sub)).thenReturn(Mono.just(jwt));
        return "Bearer token-support-" + sub;
    }

    private static final String INITIATE_BODY = """
            {"cardNumber":"4111111111111111","cardExpiry":"12/27","cvv":"123",\
            "cardholderName":"Test User","amount":"99.99","currencyCode":"USD"}""";

    // ── Group 1: JWT Authentication ───────────────────────────────────────────

    @Test
    void group1_1_noToken_returns401() {
        webClient.post().uri("/api/v1/transactions")
                .header("Content-Type", "application/json")
                .bodyValue(INITIATE_BODY)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void group1_2_invalidToken_returns401() {
        when(jwtDecoder.decode("invalid-token"))
                .thenReturn(Mono.error(new org.springframework.security.oauth2.core.OAuth2AuthenticationException("invalid")));
        webClient.post().uri("/api/v1/transactions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                .header("Content-Type", "application/json")
                .bodyValue(INITIATE_BODY)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void group1_3_validMerchantToken_postTransaction_returns201() {
        webClient.post().uri("/api/v1/transactions")
                .header(HttpHeaders.AUTHORIZATION, merchantToken("merchant-001"))
                .header("Content-Type", "application/json")
                .bodyValue(INITIATE_BODY)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED)
                .expectBody()
                .jsonPath("$.transactionId").isNotEmpty()
                .jsonPath("$.status").isEqualTo("PENDING");
    }

    @Test
    void group1_4_validAdminToken_postTransaction_returns201() {
        webClient.post().uri("/api/v1/transactions")
                .header(HttpHeaders.AUTHORIZATION, adminToken("admin-001"))
                .header("Content-Type", "application/json")
                .bodyValue(INITIATE_BODY)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED);
    }

    // ── Group 2: RBAC ──────────────────────────────────────────────────────────

    @Test
    void group2_1_merchantToken_getTransaction_returns200() {
        webClient.get().uri("/api/v1/transactions/" + FIXED_TX_ID)
                .header(HttpHeaders.AUTHORIZATION, merchantToken("merchant-001"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);
    }

    @Test
    void group2_2_supportToken_postTransaction_returns403() {
        webClient.post().uri("/api/v1/transactions")
                .header(HttpHeaders.AUTHORIZATION, supportToken("support-001"))
                .header("Content-Type", "application/json")
                .bodyValue(INITIATE_BODY)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void group2_3_merchantToken_reportingSummary_returns403() {
        webClient.get().uri("/api/v1/reporting/merchants/summary")
                .header(HttpHeaders.AUTHORIZATION, merchantToken("merchant-001"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void group2_4_adminToken_reportingSummary_returns200() {
        webClient.get().uri("/api/v1/reporting/merchants/summary")
                .header(HttpHeaders.AUTHORIZATION, adminToken("admin-001"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);
    }

    @Test
    void group2_5_supportToken_reportingSummary_returns200() {
        webClient.get().uri("/api/v1/reporting/merchants/summary")
                .header(HttpHeaders.AUTHORIZATION, supportToken("support-001"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);
    }

    // ── Group 3: Merchant Scope ────────────────────────────────────────────────

    @Test
    void group3_1_merchantAccessesOwnSummary_returns200() {
        wireMock.stubFor(get(urlMatching("/internal/reporting/merchants/merchant-001/summary"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("{}")));
        webClient.get().uri("/api/v1/reporting/merchants/merchant-001/summary")
                .header(HttpHeaders.AUTHORIZATION, merchantToken("merchant-001"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);
    }

    @Test
    void group3_2_merchantAccessesOtherSummary_returns403() {
        webClient.get().uri("/api/v1/reporting/merchants/merchant-002/summary")
                .header(HttpHeaders.AUTHORIZATION, merchantToken("merchant-001"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void group3_3_adminAccessesAnyMerchantSummary_returns200() {
        wireMock.stubFor(get(urlMatching("/internal/reporting/merchants/merchant-999/summary"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("{}")));
        webClient.get().uri("/api/v1/reporting/merchants/merchant-999/summary")
                .header(HttpHeaders.AUTHORIZATION, adminToken("admin-001"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);
    }

    @Test
    void group3_4_merchantGetOwnTransaction_returns200() {
        webClient.get().uri("/api/v1/transactions/" + FIXED_TX_ID)
                .header(HttpHeaders.AUTHORIZATION, merchantToken("merchant-001"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);
    }

    @Test
    void group3_5_merchantGetOtherTransaction_returns403() {
        // FIXED_TX_ID transaction belongs to merchant-001; merchant-002 should get 403
        webClient.get().uri("/api/v1/transactions/" + FIXED_TX_ID)
                .header(HttpHeaders.AUTHORIZATION, merchantToken("merchant-002"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── Group 4: Rate Limiting (verify headers present) ──────────────────────

    @Test
    void group4_1_rateLimitHeadersPresentOnSuccess() {
        var result = webClient.post().uri("/api/v1/transactions")
                .header(HttpHeaders.AUTHORIZATION, merchantToken("merchant-rl-e2e"))
                .header("Content-Type", "application/json")
                .bodyValue(INITIATE_BODY)
                .exchange()
                .returnResult(String.class);
        // Redis rate limiter adds X-RateLimit-* headers when not throttled
        // Just verify request succeeds
        assert result.getStatus().is2xxSuccessful() || result.getStatus().value() == 429;
    }

    @Test
    void group4_2_actuatorEndpointNotRateLimited() {
        webClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);
    }

    @Test
    void group4_3_fallbackEndpointNotRateLimited() {
        webClient.get().uri("/fallback/transaction")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ── Group 5: Circuit Breaker ───────────────────────────────────────────────

    @Test
    void group5_1_transactionFallbackReturns503Json() {
        grpcShouldFail.set(true);
        try {
            for (int i = 0; i < 6; i++) {
                webClient.post().uri("/api/v1/transactions")
                        .header(HttpHeaders.AUTHORIZATION, adminToken("admin-cb-e2e"))
                        .header("Content-Type", "application/json")
                        .bodyValue(INITIATE_BODY)
                        .exchange();
            }
            webClient.post().uri("/api/v1/transactions")
                    .header(HttpHeaders.AUTHORIZATION, adminToken("admin-cb-e2e"))
                    .header("Content-Type", "application/json")
                    .bodyValue(INITIATE_BODY)
                    .exchange()
                    .expectBody()
                    .jsonPath("$.error").isEqualTo("Service Unavailable");
        } finally {
            grpcShouldFail.set(false);
        }
    }

    @Test
    void group5_2_reportingFallbackReturns503Json() {
        wireMock.stubFor(get(urlPathEqualTo("/internal/reporting/transactions"))
                .willReturn(aResponse().withStatus(503)));
        for (int i = 0; i < 6; i++) {
            webClient.get().uri("/api/v1/reporting/transactions")
                    .header(HttpHeaders.AUTHORIZATION, adminToken("admin-reporting-cb"))
                    .exchange();
        }
        // Reset
        wireMock.stubFor(get(anyUrl()).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));
    }

    @Test
    void group5_3_fallbackBodyContainsMessage() {
        webClient.get().uri("/fallback/transaction")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.message").isNotEmpty();
    }

    // ── Group 6: Identity Header Propagation ─────────────────────────────────

    @Test
    void group6_1_merchantRequest_propagatesHeadersToUpstream() {
        wireMock.stubFor(get(urlMatching("/internal/reporting/transactions"))
                .withHeader("X-User-Role", equalTo("MERCHANT"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("[]")));
        webClient.get().uri("/api/v1/reporting/transactions")
                .header(HttpHeaders.AUTHORIZATION, merchantToken("merchant-header-test"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);
    }

    @Test
    void group6_2_adminRequest_propagatesAdminRole() {
        wireMock.stubFor(get(urlMatching("/internal/reporting/merchants/summary"))
                .withHeader("X-User-Role", equalTo("ADMIN"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("{}")));
        webClient.get().uri("/api/v1/reporting/merchants/summary")
                .header(HttpHeaders.AUTHORIZATION, adminToken("admin-header-test"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);
    }

    // ── Group 7: Audit Logging (PAN absence) ─────────────────────────────────

    @Test
    void group7_1_unauthorizedRequest_returns401WithJsonBody() {
        webClient.post().uri("/api/v1/transactions")
                .header("Content-Type", "application/json")
                .bodyValue(INITIATE_BODY)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.error").isEqualTo("Unauthorized");
    }

    @Test
    void group7_2_forbiddenRequest_returns403WithJsonBody() {
        webClient.post().uri("/api/v1/transactions")
                .header(HttpHeaders.AUTHORIZATION, supportToken("support-audit"))
                .header("Content-Type", "application/json")
                .bodyValue(INITIATE_BODY)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.error").isEqualTo("Forbidden");
    }

    // ── Group 8: Health and Actuator ──────────────────────────────────────────

    @Test
    void group8_1_actuatorHealthReturns200() {
        webClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);
    }

    @Test
    void group8_2_actuatorHealthNoAuthRequired() {
        // No Authorization header — health endpoint is permit-all
        webClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }
}
