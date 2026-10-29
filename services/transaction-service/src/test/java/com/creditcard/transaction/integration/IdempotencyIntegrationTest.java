package com.creditcard.transaction.integration;

import com.creditcard.transaction.api.grpc.TransactionGrpcService;
import com.creditcard.transaction.domain.TransactionStatus;
import com.creditcard.transaction.event.StatusUpdateEvent;
import com.creditcard.transaction.grpc.v1.InitiateTransactionRequest;
import com.creditcard.transaction.grpc.v1.InitiateTransactionResponse;
import com.creditcard.transaction.repository.ProcessedEventRepository;
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
import org.springframework.vault.support.VaultTransitContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
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
class IdempotencyIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15-alpine");

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
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    @Qualifier("statusKafkaTemplate")
    private KafkaTemplate<String, StatusUpdateEvent> statusKafkaTemplate;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID createPendingTransaction() {
        VaultTransitOperations transitOps = mock(VaultTransitOperations.class);
        when(vaultTemplate.opsForTransit()).thenReturn(transitOps);
        when(transitOps.encrypt(anyString(), any(byte[].class), any(VaultTransitContext.class)))
                .thenReturn("vault:v1:test-token");

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
    void duplicateAuthorizedEventShouldInsertExactlyOneProcessedEventsRow() {
        UUID txId = createPendingTransaction();
        StatusUpdateEvent event = buildStatusEvent("TransactionAuthorized", txId);
        String expectedKey = "tx:TransactionAuthorized:" + event.getEventId();

        // First delivery → status becomes AUTHORIZED
        statusKafkaTemplate.send("transaction.status.updated", txId.toString(), event);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                        .isEqualTo(TransactionStatus.AUTHORIZED));

        // Second delivery of the identical event (same eventId)
        statusKafkaTemplate.send("transaction.status.updated", txId.toString(), event);

        // Wait for the second message to be consumed (key already in processed_events)
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(processedEventRepository.existsByEventKey(expectedKey)).isTrue());

        // Status unchanged; PK constraint guarantees the row count is exactly one
        assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.AUTHORIZED);
        assertThat(processedEventRepository.existsByEventKey(expectedKey)).isTrue();
    }

    @Test
    void tenDeliveriesOfSameFailedEventShouldProduceExactlyOneStateChange() {
        UUID txId = createPendingTransaction();
        StatusUpdateEvent event = buildStatusEvent("TransactionFailed", txId);
        String expectedKey = "tx:TransactionFailed:" + event.getEventId();

        // Send the same event 10 times
        for (int i = 0; i < 10; i++) {
            statusKafkaTemplate.send("transaction.status.updated", txId.toString(), event);
        }

        // Wait for first delivery to be processed → status FAILED
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                        .isEqualTo(TransactionStatus.FAILED));

        // Wait for all 10 to finish (processed_events row must exist; no exception thrown)
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(processedEventRepository.existsByEventKey(expectedKey)).isTrue());

        // Final assertions: single FAILED state, no DataIntegrityViolationException surfaced
        assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    void terminalGuardAndIdempotencyTogetherBlockDuplicateAuthorized() {
        UUID txId = createPendingTransaction();

        // First event: PENDING → AUTHORIZED
        statusKafkaTemplate.send("transaction.status.updated", txId.toString(),
                buildStatusEvent("TransactionAuthorized", txId));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                        .isEqualTo(TransactionStatus.AUTHORIZED));

        // Second event: different eventId (new UUID) so idempotency key differs —
        // only the terminal-state guard should prevent the overwrite
        statusKafkaTemplate.send("transaction.status.updated", txId.toString(),
                buildStatusEvent("TransactionAuthorized", txId));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                        .isEqualTo(TransactionStatus.AUTHORIZED));
    }
}
