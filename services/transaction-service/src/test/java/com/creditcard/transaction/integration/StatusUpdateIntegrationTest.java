package com.creditcard.transaction.integration;

import com.creditcard.transaction.api.grpc.TransactionGrpcService;
import com.creditcard.transaction.domain.TransactionStatus;
import com.creditcard.transaction.event.StatusUpdateEvent;
import com.creditcard.transaction.grpc.v1.InitiateTransactionRequest;
import com.creditcard.transaction.grpc.v1.InitiateTransactionResponse;
import com.creditcard.transaction.repository.TransactionRepository;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
class StatusUpdateIntegrationTest {

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

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    @Qualifier("statusKafkaTemplate")
    private KafkaTemplate<String, StatusUpdateEvent> statusKafkaTemplate;

    // ── Helper: create a PENDING transaction and return its ID ────────────────

    private UUID createPendingTransaction() {
        VaultTransitOperations transitOps = mock(VaultTransitOperations.class);
        when(vaultTemplate.opsForTransit()).thenReturn(transitOps);
        when(transitOps.encrypt(anyString(), any(Plaintext.class)))
                .thenReturn(Ciphertext.of("vault:v1:test-token"));

        AtomicReference<UUID> txId = new AtomicReference<>();
        transactionGrpcService.initiateTransaction(
                InitiateTransactionRequest.newBuilder()
                        .setCardNumber("4111111111111111")
                        .setCardExpiry("12/27").setCvv("123")
                        .setCardholderName("Test User")
                        .setAmount("50.00").setCurrencyCode("USD")
                        .build(),
                new StreamObserver<>() {
                    @Override public void onNext(InitiateTransactionResponse r) {
                        txId.set(UUID.fromString(r.getTransactionId()));
                    }
                    @Override public void onError(Throwable t) { throw new RuntimeException(t); }
                    @Override public void onCompleted() {}
                });
        return txId.get();
    }

    private StatusUpdateEvent buildStatusEvent(String eventType, UUID transactionId) {
        StatusUpdateEvent.Payload payload = new StatusUpdateEvent.Payload();
        payload.setTransactionId(transactionId.toString());

        StatusUpdateEvent event = new StatusUpdateEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(eventType);
        event.setSourceService("authorization-service");
        event.setOccurredAt(Instant.now());
        event.setPayload(payload);
        return event;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void pendingTransactionShouldBecomeAuthorized() {
        UUID txId = createPendingTransaction();
        assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PENDING);

        statusKafkaTemplate.send("transaction.status.updated", txId.toString(),
                buildStatusEvent("TransactionAuthorized", txId));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                        .isEqualTo(TransactionStatus.AUTHORIZED));
    }

    @Test
    void pendingTransactionShouldBecomeDeclined() {
        UUID txId = createPendingTransaction();

        statusKafkaTemplate.send("transaction.status.updated", txId.toString(),
                buildStatusEvent("TransactionDeclined", txId));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                        .isEqualTo(TransactionStatus.DECLINED));
    }

    @Test
    void duplicateAuthorizedEventShouldNotChangeStatusAgain() {
        UUID txId = createPendingTransaction();
        StatusUpdateEvent event = buildStatusEvent("TransactionAuthorized", txId);

        // First delivery
        statusKafkaTemplate.send("transaction.status.updated", txId.toString(), event);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                        .isEqualTo(TransactionStatus.AUTHORIZED));

        // Second delivery of the same event (same eventId = same idempotency key)
        statusKafkaTemplate.send("transaction.status.updated", txId.toString(), event);

        // Status must stay AUTHORIZED, no exception, processed_events has exactly one row for this key
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                        .isEqualTo(TransactionStatus.AUTHORIZED));
    }

    // ── US3: FAILED scenarios ─────────────────────────────────────────────────

    @Test
    void pendingTransactionShouldBecomeFailed() {
        UUID txId = createPendingTransaction();

        statusKafkaTemplate.send("transaction.status.updated", txId.toString(),
                buildStatusEvent("TransactionFailed", txId));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                        .isEqualTo(TransactionStatus.FAILED));
    }

    @Test
    void authorizedTransactionShouldIgnoreFailedEvent() {
        UUID txId = createPendingTransaction();

        // First: AUTHORIZED
        statusKafkaTemplate.send("transaction.status.updated", txId.toString(),
                buildStatusEvent("TransactionAuthorized", txId));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                        .isEqualTo(TransactionStatus.AUTHORIZED));

        // FAILED event should be ignored — terminal guard blocks it
        statusKafkaTemplate.send("transaction.status.updated", txId.toString(),
                buildStatusEvent("TransactionFailed", txId));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                        .isEqualTo(TransactionStatus.AUTHORIZED));
    }

    @Test
    void terminalTransactionShouldIgnoreSubsequentStatusEvent() {
        UUID txId = createPendingTransaction();

        // First: AUTHORIZED
        statusKafkaTemplate.send("transaction.status.updated", txId.toString(),
                buildStatusEvent("TransactionAuthorized", txId));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                        .isEqualTo(TransactionStatus.AUTHORIZED));

        // Second: DECLINED (different eventId, but terminal guard should block it)
        statusKafkaTemplate.send("transaction.status.updated", txId.toString(),
                buildStatusEvent("TransactionDeclined", txId));

        // Brief wait then assert still AUTHORIZED
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                        .isEqualTo(TransactionStatus.AUTHORIZED));
    }
}
