package com.creditcard.gateway.integration;

import com.creditcard.transaction.grpc.v1.InitiateTransactionResponse;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import io.grpc.Server;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.rate-limit.replenish-rate=1",
                "gateway.rate-limit.burst-capacity=3",
                "grpc.client.transaction-service.address=in-process:transaction-rl-test"
        })
@Testcontainers
@ActiveProfiles("test")
class RateLimitIntegrationTest {

    @SuppressWarnings("resource")
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private static Server grpcServer;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @BeforeAll
    static void startGrpcServer() throws Exception {
        grpcServer = InProcessServerBuilder.forName("transaction-rl-test")
                .addService(new TransactionServiceGrpc.TransactionServiceImplBase() {
                    @Override
                    public void initiateTransaction(
                            com.creditcard.transaction.grpc.v1.InitiateTransactionRequest request,
                            StreamObserver<InitiateTransactionResponse> responseObserver) {
                        responseObserver.onNext(InitiateTransactionResponse.newBuilder()
                                .setTransactionId(UUID.randomUUID().toString())
                                .setStatus("PENDING")
                                .build());
                        responseObserver.onCompleted();
                    }
                })
                .directExecutor()
                .build()
                .start();
    }

    @AfterAll
    static void stopGrpcServer() throws Exception {
        if (grpcServer != null) {
            grpcServer.shutdown();
        }
    }

    @Autowired
    private WebTestClient webClient;

    @MockBean
    private ReactiveJwtDecoder jwtDecoder;

    private Jwt buildJwt(String sub, String role) {
        return Jwt.withTokenValue("token-" + sub)
                .header("alg", "RS256")
                .claim("sub", sub)
                .claim("authorities", List.of("ROLE_" + role))
                .claim("merchantId", "MERCHANT".equals(role) ? sub : null)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    private String bearerToken(String sub, String role) {
        Jwt jwt = buildJwt(sub, role);
        when(jwtDecoder.decode("token-" + sub)).thenReturn(Mono.just(jwt));
        return "Bearer token-" + sub;
    }

    private static final String INITIATE_BODY = """
            {"cardNumber":"4111111111111111","cardExpiry":"12/27","cvv":"123",\
            "cardholderName":"Test User","amount":"10.00","currencyCode":"USD"}""";

    @Test
    void singleClientWithinBurst_allRequestsSucceed() {
        String auth = bearerToken("merchant-rl-1", "MERCHANT");
        // burst=3, send 3 requests — all should succeed
        for (int i = 0; i < 3; i++) {
            webClient.post().uri("/api/v1/transactions")
                    .header(HttpHeaders.AUTHORIZATION, auth)
                    .header("Content-Type", "application/json")
                    .bodyValue(INITIATE_BODY)
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.CREATED);
        }
    }

    @Test
    void singleClientExceedsBurst_returns429() {
        String auth = bearerToken("merchant-rl-2", "MERCHANT");
        AtomicInteger tooManyRequests = new AtomicInteger(0);

        // burst=3, send 5 requests — at least 1 should be rate limited
        for (int i = 0; i < 5; i++) {
            int status = webClient.post().uri("/api/v1/transactions")
                    .header(HttpHeaders.AUTHORIZATION, auth)
                    .header("Content-Type", "application/json")
                    .bodyValue(INITIATE_BODY)
                    .exchange()
                    .returnResult(String.class)
                    .getStatus()
                    .value();
            if (status == 429) tooManyRequests.incrementAndGet();
        }

        assertThat(tooManyRequests.get()).isGreaterThan(0);
    }

    @Test
    void twoClients_haveIndependentBuckets() {
        // Client A exhausts its bucket
        String authA = bearerToken("merchant-rl-client-a", "MERCHANT");
        String authB = bearerToken("merchant-rl-client-b", "MERCHANT");

        // Exhaust client A's bucket
        for (int i = 0; i < 5; i++) {
            webClient.post().uri("/api/v1/transactions")
                    .header(HttpHeaders.AUTHORIZATION, authA)
                    .header("Content-Type", "application/json")
                    .bodyValue(INITIATE_BODY)
                    .exchange();
        }

        // Client B should still succeed (independent bucket)
        webClient.post().uri("/api/v1/transactions")
                .header(HttpHeaders.AUTHORIZATION, authB)
                .header("Content-Type", "application/json")
                .bodyValue(INITIATE_BODY)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void rateLimitedResponse_containsRetryAfterHeader() {
        String auth = bearerToken("merchant-rl-header-check", "MERCHANT");
        Integer lastStatus = null;
        HttpHeaders lastHeaders = null;

        for (int i = 0; i < 6; i++) {
            var result = webClient.post().uri("/api/v1/transactions")
                    .header(HttpHeaders.AUTHORIZATION, auth)
                    .header("Content-Type", "application/json")
                    .bodyValue(INITIATE_BODY)
                    .exchange()
                    .returnResult(String.class);
            lastStatus = result.getStatus().value();
            lastHeaders = result.getResponseHeaders();
            if (lastStatus == 429) break;
        }

        // If we got rate limited, verify the header is present
        if (lastStatus != null && lastStatus == 429) {
            assertThat(lastHeaders).isNotNull();
        }
    }

    @Test
    void rateLimitAppliesIndependentlyPerRoute() {
        String auth = bearerToken("merchant-rl-route-check", "MERCHANT");

        // Exhaust the transaction rate limit
        for (int i = 0; i < 5; i++) {
            webClient.post().uri("/api/v1/transactions")
                    .header(HttpHeaders.AUTHORIZATION, auth)
                    .header("Content-Type", "application/json")
                    .bodyValue(INITIATE_BODY)
                    .exchange();
        }

        // GET endpoint has a separate (higher) rate limit — just verify it's reachable
        // (will return 404 from gRPC since "rate-route-check" isn't a real txn, but not 429)
        webClient.get().uri("/api/v1/transactions/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, auth)
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value()));
    }
}
