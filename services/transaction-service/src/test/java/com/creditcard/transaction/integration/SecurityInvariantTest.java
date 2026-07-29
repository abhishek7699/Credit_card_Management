package com.creditcard.transaction.integration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.creditcard.transaction.api.grpc.TransactionGrpcService;
import com.creditcard.transaction.grpc.v1.InitiateTransactionRequest;
import com.creditcard.transaction.grpc.v1.InitiateTransactionResponse;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.core.VaultTransitOperations;
import org.springframework.vault.support.Ciphertext;
import org.springframework.vault.support.Plaintext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {"transaction.initiated", "transaction.status.updated"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@ActiveProfiles("test")
class SecurityInvariantTest {

    private static final Pattern PAN_PATTERN = Pattern.compile("\\b\\d{13,19}\\b");
    private static final String TEST_PAN = "4111111111111111";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockBean
    private VaultTemplate vaultTemplate;

    @Autowired
    private TransactionGrpcService transactionGrpcService;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.detachAppender(logAppender);
    }

    @Test
    void rawPanShouldNeverAppearInAnyLogMessageDuringTransactionCreation() {
        VaultTransitOperations transitOps = mock(VaultTransitOperations.class);
        when(vaultTemplate.opsForTransit()).thenReturn(transitOps);
        when(transitOps.encrypt(anyString(), any(Plaintext.class)))
                .thenReturn(Ciphertext.of("vault:v1:opaque-token-xyz"));

        AtomicReference<String> txId = new AtomicReference<>();
        transactionGrpcService.initiateTransaction(
                InitiateTransactionRequest.newBuilder()
                        .setCardNumber(TEST_PAN)
                        .setCardExpiry("12/27")
                        .setCvv("123")
                        .setCardholderName("Security Test User")
                        .setAmount("50.00")
                        .setCurrencyCode("USD")
                        .build(),
                new StreamObserver<>() {
                    @Override public void onNext(InitiateTransactionResponse r) { txId.set(r.getTransactionId()); }
                    @Override public void onError(Throwable t) { throw new RuntimeException(t); }
                    @Override public void onCompleted() {}
                });

        assertThat(txId.get()).isNotNull();

        // Collect all log messages produced during this test
        List<ILoggingEvent> events = logAppender.list;
        String allLogMessages = events.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));

        // The raw PAN must not appear literally in any log line
        assertThat(allLogMessages)
                .as("Raw PAN must never appear in log output")
                .doesNotContain(TEST_PAN);

        // No 13-19 digit sequence should appear in any log line
        assertThat(PAN_PATTERN.matcher(allLogMessages).find())
                .as("No PAN-like sequence (13-19 digits) should appear in logs")
                .isFalse();
    }
}
