package com.creditcard.transaction.integration;

import com.creditcard.transaction.domain.Transaction;
import com.creditcard.transaction.domain.TransactionStatus;
import com.creditcard.transaction.repository.OutboxRepository;
import com.creditcard.transaction.repository.TransactionRepository;
import com.creditcard.transaction.service.OutboxPublisher;
import com.creditcard.transaction.service.OutboxRelay;
import com.creditcard.transaction.service.TransactionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.vault.core.VaultTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * 013 outbox guarantees, against real Postgres and embedded Kafka. The scheduled relay is
 * off (transaction.outbox.relay-enabled=false) so each step is driven by the test.
 */
@SpringBootTest(properties = "transaction.outbox.relay-enabled=false")
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {"transaction.status.updated", "transaction.authorized"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@ActiveProfiles("test")
class OutboxRelayIntegrationTest {

    private static final String TOPIC = "transaction.status.updated";
    private static final String STATUS_TOPIC = "transaction.status.updated";

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

    /** Spied so a test can skip the immediate post-commit publish and leave the row for the relay. */
    @SpyBean
    private OutboxPublisher outboxPublisher;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * Crash between commit and publish: the decision is committed but the process dies before
     * the relay sends it. The row must survive and the next relay pass must publish it.
     */
    @Test
    void crashAfterCommitBeforePublishIsRecoveredByTheRelay() {
        UUID txId = saveTransaction();

        // The "crash": the immediate publish after commit fails, so the committed row stays unsent.
        doThrow(new IllegalStateException("simulated crash before publish")).when(outboxPublisher).publish(any());
        transactionService.applyAuthorizationResult(txId.toString(), "AUTHORIZED", null);
        // "Restart": the publisher works again; only the relay can send the row now.
        doCallRealMethod().when(outboxPublisher).publish(any());

        UUID eventId = TransactionService.stableEventId(txId, STATUS_TOPIC, "TransactionAuthorized");
        assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.AUTHORIZED);
        assertThat(outboxRepository.findById(eventId)).isPresent();
        assertThat(outboxRepository.findById(eventId).orElseThrow().getSentAt()).isNull();
        assertThat(readAll(txId)).isEmpty();

        // Recovery: the relay publishes the row that survived the crash.
        outboxRelay.relayBatch();

        assertThat(outboxRepository.findById(eventId).orElseThrow().getSentAt()).isNotNull();
        List<JsonNode> published = readAll(txId);
        assertThat(published).hasSize(1);
        assertThat(published.get(0).get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(published.get(0).get("eventType").asText()).isEqualTo("TransactionAuthorized");
        assertThat(published.get(0).get("payload").get("outcome").asText()).isEqualTo("AUTHORIZED");
    }

    /**
     * Relay publishes the same row twice (for example, the mark-as-sent write was lost after a
     * successful send). Both copies carry the same eventId and bytes, so the consumer's
     * processed_events check drops the second one. The state changes once and the outbox has one row.
     */
    @Test
    void relayPublishingTwiceProducesIdenticalEventsAndOneStateChange() {
        UUID txId = saveTransaction();
        transactionService.applyAuthorizationResult(txId.toString(), "DECLINED", "51");
        UUID eventId = TransactionService.stableEventId(txId, STATUS_TOPIC, "TransactionDeclined");

        outboxRelay.relayBatch();
        // Simulate the lost mark: the row looks unsent again, so the relay sends it a second time.
        jdbcTemplate.update("UPDATE transaction_outbox SET sent_at = NULL WHERE event_id = ?", eventId);
        outboxRelay.relayBatch();

        List<JsonNode> published = readAll(txId);
        assertThat(published).hasSize(2);
        assertThat(published.get(0).get("eventId").asText())
                .isEqualTo(published.get(1).get("eventId").asText())
                .isEqualTo(eventId.toString());
        assertThat(published.get(0)).isEqualTo(published.get(1));

        // The consumer applies it once: a second identical result is a dedupe, not a change.
        transactionService.applyAuthorizationResult(txId.toString(), "AUTHORIZED", null);
        assertThat(transactionRepository.findById(txId).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.DECLINED);
        assertThat(outboxRepository.findById(
                TransactionService.stableEventId(txId, "transaction.authorized", "TransactionAuthorized")))
                .isEmpty();
    }

    /**
     * One row that cannot be published (stored JSON is invalid) must not block other transactions. It
     * blocks only its own transaction, and it stops being retried once it reaches the attempt limit
     * (default 5), after which its transaction's later event goes out.
     */
    @Test
    void unpublishableRowBlocksOnlyItsOwnTransactionAndIsMarkedFailedAfterTheLimit() throws Exception {
        UUID poisonId = UUID.randomUUID();
        UUID sameTxId = UUID.randomUUID();
        UUID otherTxId = UUID.randomUUID();
        UUID poisonTx = UUID.randomUUID();
        UUID otherTx = UUID.randomUUID();
        double failedBefore = meterRegistry.counter("transaction.outbox.failed").count();

        insertRow(poisonId, poisonTx, "not json");
        insertRow(sameTxId, poisonTx, json(sameTxId, poisonTx));
        insertRow(otherTxId, otherTx, json(otherTxId, otherTx));

        outboxRelay.relayBatch();
        assertThat(sent(otherTxId)).isTrue();
        assertThat(sent(sameTxId)).isFalse();
        assertThat(attempts(poisonId)).isEqualTo(1);

        for (int pass = 2; pass <= 5; pass++) {
            outboxRelay.relayBatch();
        }
        assertThat(failed(poisonId)).isTrue();
        assertThat(lastError(poisonId)).contains("not valid JSON");
        assertThat(meterRegistry.counter("transaction.outbox.failed").count()).isEqualTo(failedBefore + 1);

        outboxRelay.relayBatch();
        assertThat(sent(sameTxId)).isTrue();
        assertThat(readAll(otherTx)).hasSize(1);
    }

    /**
     * Inserts a committed unsent row directly. The test profile builds the schema from the entity
     * (Flyway is off), so every NOT NULL column is supplied here, as the entity would write it.
     */
    private void insertRow(UUID eventId, UUID txId, String payload) {
        jdbcTemplate.update("INSERT INTO transaction_outbox "
                + "(event_id, topic, message_key, event_type, payload, created_at, attempts) "
                + "VALUES (?, ?, ?, ?, ?, now(), 0)",
                eventId, TOPIC, txId.toString(), "TransactionAuthorized", payload);
    }

    private String json(UUID eventId, UUID txId) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "eventId", eventId.toString(),
                "eventType", "TransactionAuthorized",
                "payload", java.util.Map.of("transactionId", txId.toString(), "outcome", "AUTHORIZED")));
    }

    private boolean sent(UUID eventId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT sent_at IS NOT NULL FROM transaction_outbox WHERE event_id = ?", Boolean.class, eventId));
    }

    private boolean failed(UUID eventId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT failed_at IS NOT NULL FROM transaction_outbox WHERE event_id = ?", Boolean.class, eventId));
    }

    private int attempts(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT attempts FROM transaction_outbox WHERE event_id = ?", Integer.class, eventId);
    }

    private String lastError(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_error FROM transaction_outbox WHERE event_id = ?", String.class, eventId);
    }

    private UUID saveTransaction() {
        Transaction tx = new Transaction();
        tx.setCardToken("vault:v1:outbox-test");
        tx.setMaskedCardNumber("****-****-****-4242");
        tx.setCardholderName("Outbox Test");
        tx.setAmount(new BigDecimal("12.34"));
        tx.setCurrencyCode("USD");
        tx.setStatus(TransactionStatus.PENDING);
        return transactionRepository.save(tx).getId();
    }

    /** Reads every record on the topic for one transaction, from the beginning. */
    private List<JsonNode> readAll(UUID txId) {
        List<JsonNode> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps())) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.currentTimeMillis() + Duration.ofSeconds(3).toMillis();
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    try {
                        JsonNode node = objectMapper.readTree(record.value());
                        if (txId.toString().equals(node.path("payload").path("transactionId").asText())) {
                            found.add(node);
                        }
                    } catch (Exception ignored) {
                        // not an envelope we care about
                    }
                }
            }
        }
        return found;
    }

    private java.util.Map<String, Object> consumerProps() {
        return java.util.Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    }
}
