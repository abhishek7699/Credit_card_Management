package com.creditcard.gateway.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Proves the timeout-ordering fix: booking-submit's own circuit-breaker/time-limiter
 * timeout (10s) exceeds booking-service's 5s gRPC deadline to transaction-service, so a
 * backend call slower than 5s but faster than 10s still succeeds through the gateway —
 * while the OTHER booking routes (room-search, sharing "booking-service-cb") are
 * deliberately left at the unchanged 5s default and still fall back on the same delay.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.rate-limit.replenish-rate=100",
                "gateway.rate-limit.burst-capacity=1000"
        })
@Testcontainers
@ActiveProfiles("test")
class BookingCircuitBreakerIntegrationTest {

    @SuppressWarnings("resource")
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static WireMockServer wireMock = new WireMockServer(
            WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        wireMock.start();
    }

    @AfterAll
    static void stopServers() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("BOOKING_SERVICE_URL", () -> "http://localhost:" + wireMock.port());
    }

    @Autowired
    private WebTestClient webClient;

    @MockBean
    private ReactiveJwtDecoder jwtDecoder;

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    private String frontDeskBearerToken(String sub) {
        Jwt jwt = Jwt.withTokenValue("token-" + sub)
                .header("alg", "RS256")
                .claim("sub", sub)
                .claim("authorities", List.of("ROLE_FRONT_DESK"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode("token-" + sub)).thenReturn(Mono.just(jwt));
        return "Bearer token-" + sub;
    }

    @Test
    void bookingSubmit_survivesA7SecondBackendDelay_thatExceedsTheOldFiveSecondTimeout() {
        wireMock.stubFor(post(urlPathEqualTo("/api/v1/bookings")).willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withFixedDelay(7000)
                .withBody("{\"bookingId\":\"11111111-1111-1111-1111-111111111111\",\"status\":\"HELD\"}")));

        String auth = frontDeskBearerToken("staff-cb-1");
        Instant start = Instant.now();

        webClient.mutate().responseTimeout(Duration.ofSeconds(15)).build()
                .post().uri("/api/v1/bookings")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .header("Content-Type", "application/json")
                .bodyValue("{\"roomId\":\"11111111-1111-1111-1111-111111111111\","
                        + "\"checkIn\":\"2026-12-01\",\"checkOut\":\"2026-12-03\",\"guestName\":\"Jane\","
                        + "\"card\":{\"cardNumber\":\"4111111111111111\",\"expiry\":\"12/28\","
                        + "\"cvv\":\"123\",\"cardholderName\":\"Jane\"}}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("HELD");

        Duration elapsed = Duration.between(start, Instant.now());
        assertThat(elapsed).isGreaterThan(Duration.ofSeconds(5)); // genuinely waited past the old timeout
        assertThat(elapsed).isLessThan(Duration.ofSeconds(10));   // and within the new one
    }

    @Test
    void roomSearch_stillFallsBackOnTheSameSevenSecondDelay_becauseItsBreakerStaysAtFiveSeconds() {
        wireMock.stubFor(get(urlPathEqualTo("/api/v1/rooms")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withFixedDelay(7000)
                .withBody("[]")));

        String auth = frontDeskBearerToken("staff-cb-2");

        webClient.mutate().responseTimeout(Duration.ofSeconds(15)).build()
                .get().uri("/api/v1/rooms?checkIn=2026-12-01&checkOut=2026-12-03")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .exchange()
                .expectStatus().isEqualTo(503);
    }
}
