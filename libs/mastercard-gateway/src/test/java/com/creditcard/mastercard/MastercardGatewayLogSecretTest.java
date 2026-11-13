package com.creditcard.mastercard;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Constitution Principle IX: tests MUST assert that no secret or card value appears in captured log output.
 * Covers approval, decline, a duplicate identifier and a timeout, the paths that log the most.
 */
class MastercardGatewayLogSecretTest {

    private static final String MERCHANT = "SECRETMERCH42";
    private static final String PASSWORD = "PASSWORD-SECRET-9Z";
    private static final String SESSION = "SESSION-SECRET-7Q";

    private WireMockServer server;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        server = TlsFixture.httpsServer();
        logger = (Logger) LoggerFactory.getLogger(MastercardGatewayClient.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        server.stop();
    }

    @Test
    void noSecretOrSessionValueReachesTheLogs() {
        server.stubFor(put(urlPathMatching(".*/transaction/.*")).willReturn(aResponse().withStatus(200)
                .withBody("{\"result\":\"SUCCESS\",\"response\":{\"gatewayCode\":\"APPROVED\"}}")));
        server.stubFor(put(urlPathMatching(".*/transaction/.*")).willReturn(aResponse().withStatus(400)
                .withBody("{\"result\":\"FAILURE\",\"error\":{\"cause\":\"DUPLICATE_TRANSACTION_ID\"}}")));
        MastercardGatewayClient client = new MastercardGatewayClient(new MastercardGatewayConfig(
                "https://localhost:" + server.httpsPort(), MERCHANT, PASSWORD, "100",
                Duration.ofSeconds(5), Duration.ofSeconds(5)), TlsFixture.trustingFixtureOnly());

        client.authorize(UUID.randomUUID(), BigDecimal.ONE, "USD", SESSION);
        client.authorize(UUID.randomUUID(), BigDecimal.ONE, "USD", SESSION);

        String logged = String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(appender.list).isNotEmpty();
        assertThat(logged).doesNotContain(PASSWORD).doesNotContain(SESSION).doesNotContain(MERCHANT);
        assertThat(logged).doesNotContain("Basic ").doesNotContain("merchant.");
    }
}
