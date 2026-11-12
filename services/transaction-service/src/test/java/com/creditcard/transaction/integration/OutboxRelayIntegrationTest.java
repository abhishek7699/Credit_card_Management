package com.creditcard.transaction.integration;

import com.creditcard.transaction.domain.Transaction;
import com.creditcard.transaction.domain.TransactionStatus;
import com.creditcard.transaction.repository.OutboxRepository;
import com.creditcard.transaction.repository.TransactionRepository;
import com.creditcard.transaction.service.OutboxRelay;
import com.creditcard.transaction.service.TransactionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

/**
 * 013 outbox guarantees, against real Postgres and embedded Kafka. The scheduled relay is
 * off (transaction.outbox.relay-enabled=false) so each step is driven by the test.
 */
@SpringBootTest(properties = "transaction.outbox.relay-enabled=false")
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {"transaction.status.updated"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@ActiveProfiles("test")
class OutboxRelayIntegrationTest {

    private static final String TOPIC = "transaction.status.updated";

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

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * Crash between commit and publish: the decision is committed but the process dies before
     * the relay sends it. The row must survive and the next relay pass must publish it.
     */
    @Test
    void crashAfterCommitBeforePublishIsRecoveredByTheRelay() {
        UUID txId = saveTransaction();

        // Commit the outcome. The relay is off, so nothing is published: this is the "crash".
        transactionService.applyAuthorizationResult(txId.toString(), "AUTHORIZED", null);

        UUID eventId = TransactionService.stableEventId(txId, "TransactionAuthorized");
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
        UUID eventId = TransactionService.stableEventId(txId, "TransactionDeclined");

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
        assertThat(outboxRepository.findById(TransactionService.stableEventId(txId, "TransactionAuthorized")))
                .isEmpty();
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
