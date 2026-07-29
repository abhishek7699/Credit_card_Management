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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.rate-limit.replenish-rate=100",
                "gateway.rate-limit.burst-capacity=1000",
                "grpc.client.transaction-service.address=in-process:transaction-cb-test"
        })
@Testcontainers
@ActiveProfiles("test")
class CircuitBreakerIntegrationTest {

    @SuppressWarnings("resource")
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    // WireMock started statically so port is available for @DynamicPropertySource
    static WireMockServer wireMock = new WireMockServer(
            WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        wireMock.start();
    }

    private static Server grpcServer;

    // Toggle to control gRPC service behavior per test
    private static final AtomicBoolean grpcShouldFail = new AtomicBoolean(false);
    private static final AtomicBoolean grpcGetShouldFail = new AtomicBoolean(false);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("REPORTING_SERVICE_URL", () -> "http://localhost:" + wireMock.port());
    }

    @BeforeAll
    static void startServers() throws Exception {
        grpcServer = InProcessServerBuilder.forName("transaction-cb-test")
                .addService(new TransactionServiceGrpc.TransactionServiceImplBase() {
                    @Override
                    public void initiateTransaction(
                            com.creditcard.transaction.grpc.v1.InitiateTransactionRequest req,
                            StreamObserver<InitiateTransactionResponse> observer) {
                        if (grpcShouldFail.get()) {
                            observer.onError(Status.UNAVAILABLE
                                    .withDescription("Service unavailable").asRuntimeException());
                        } else {
                            observer.onNext(InitiateTransactionResponse.newBuilder()
                                    .setTransactionId(UUID.randomUUID().toString())
                                    .setStatus("PENDING")
                                    .build());
                            observer.onCompleted();
                        }
                    }

                    @Override
                    public void getTransaction(
                            com.creditcard.transaction.grpc.v1.GetTransactionRequest req,
                            StreamObserver<GetTransactionResponse> observer) {
                        if (grpcGetShouldFail.get()) {
                            observer.onError(Status.UNAVAILABLE
                                    .withDescription("Service unavailable").asRuntimeException());
                        } else {
                            observer.onNext(GetTransactionResponse.newBuilder()
                                    .setTransactionId(req.getTransactionId())
                                    .setStatus("PENDING")
                                    .setMaskedCardNumber("****-****-****-1234")
                                    .setMerchantId("merchant-001")
                                    .build());
                            observer.onCompleted();
                        }
                    }
                })
                .directExecutor()
                .build()
                .start();
    }

    @AfterAll
    static void stopServers() throws Exception {
        grpcShouldFail.set(false);
        grpcGetShouldFail.set(false);
        if (grpcServer != null) grpcServer.shutdown();
        if (wireMock != null) wireMock.stop();
    }

    @Autowired
    private WebTestClient webClient;

    @MockBean
    private ReactiveJwtDecoder jwtDecoder;

    private String bearerToken(String sub) {
        Jwt jwt = Jwt.withTokenValue("token-" + sub)
                .header("alg", "RS256")
                .claim("sub", sub)
                .claim("authorities", List.of("ROLE_ADMIN"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode("token-" + sub)).thenReturn(Mono.just(jwt));
        return "Bearer token-" + sub;
    }

    private static final String INITIATE_BODY = """
            {"cardNumber":"4111111111111111","cardExpiry":"12/27","cvv":"123",\
            "cardholderName":"CB Test","amount":"10.00","currencyCode":"USD"}""";

    @Test
    void grpcUnavailable_opensCircuit_returns503Fallback() throws Exception {
        String auth = bearerToken("admin-cb-1");
        grpcShouldFail.set(true);
        try {
            // Send requests to open the circuit (slidingWindowSize=5, failureRate=60%)
            int serviceUnavailableCount = 0;
            for (int i = 0; i < 6; i++) {
                int status = webClient.post().uri("/api/v1/transactions")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .header("Content-Type", "application/json")
                        .bodyValue(INITIATE_BODY)
                        .exchange()
                        .returnResult(String.class)
                        .getStatus()
                        .value();
                if (status == 503) serviceUnavailableCount++;
            }
            assertThat(serviceUnavailableCount).isGreaterThan(0);
        } finally {
            grpcShouldFail.set(false);
        }
    }

    @Test
    void fallback503ResponseArrivesQuickly() {
        String auth = bearerToken("admin-cb-2");
        grpcShouldFail.set(true);
        try {
            long start = System.currentTimeMillis();
            webClient.post().uri("/api/v1/transactions")
                    .header(HttpHeaders.AUTHORIZATION, auth)
                    .header("Content-Type", "application/json")
                    .bodyValue(INITIATE_BODY)
                    .exchange()
                    .returnResult(String.class);
            long elapsed = System.currentTimeMillis() - start;
            // Should respond within 6 seconds (circuit breaker timeout is 5s)
            assertThat(elapsed).isLessThan(6000);
        } finally {
            grpcShouldFail.set(false);
        }
    }

    @Test
    void circuitRecovery_afterWaitDuration() throws Exception {
        String auth = bearerToken("admin-cb-3");
        grpcShouldFail.set(true);

        // Open the circuit
        for (int i = 0; i < 6; i++) {
            webClient.post().uri("/api/v1/transactions")
                    .header(HttpHeaders.AUTHORIZATION, auth)
                    .header("Content-Type", "application/json")
                    .bodyValue(INITIATE_BODY)
                    .exchange();
        }

        // Wait for circuit to transition to HALF_OPEN (waitDurationInOpenState=1s in test config)
        Thread.sleep(1500);

        // gRPC now succeeds — circuit should recover
        grpcShouldFail.set(false);
        webClient.post().uri("/api/v1/transactions")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .header("Content-Type", "application/json")
                .bodyValue(INITIATE_BODY)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void reportingCircuit_opensOnWireMockFailure() {
        String auth = bearerToken("admin-cb-4");

        // Configure WireMock to return 503 for reporting
        wireMock.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(503)));

        int fallbackCount = 0;
        for (int i = 0; i < 6; i++) {
            int status = webClient.get().uri("/api/v1/reporting/transactions")
                    .header(HttpHeaders.AUTHORIZATION, auth)
                    .exchange()
                    .returnResult(String.class)
                    .getStatus()
                    .value();
            if (status == 503) fallbackCount++;
        }
        assertThat(fallbackCount).isGreaterThan(0);
    }

    @Test
    void reportingCircuitRecovery_afterWireMockRestored() throws Exception {
        String auth = bearerToken("admin-cb-5");

        // First open the reporting circuit
        wireMock.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(503)));
        for (int i = 0; i < 6; i++) {
            webClient.get().uri("/api/v1/reporting/transactions")
                    .header(HttpHeaders.AUTHORIZATION, auth)
                    .exchange();
        }

        Thread.sleep(1500);

        // Now restore WireMock to return 200
        wireMock.stubFor(get(anyUrl()).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("[]")));

        // Circuit should recover — we expect 200 (not 503 fallback)
        webClient.get().uri("/api/v1/reporting/transactions")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);
    }

    @Test
    void transactionAndReportingCircuits_areIndependent() throws Exception {
        String auth = bearerToken("admin-cb-6");

        // Open the transaction circuit
        grpcShouldFail.set(true);
        try {
            for (int i = 0; i < 6; i++) {
                webClient.post().uri("/api/v1/transactions")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .header("Content-Type", "application/json")
                        .bodyValue(INITIATE_BODY)
                        .exchange();
            }

            // Configure reporting to succeed
            wireMock.stubFor(get(anyUrl()).willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[]")));

            // Reporting circuit should still be operational
            webClient.get().uri("/api/v1/reporting/transactions")
                    .header(HttpHeaders.AUTHORIZATION, auth)
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.OK);
        } finally {
            grpcShouldFail.set(false);
        }
    }
}
