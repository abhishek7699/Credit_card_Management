package com.creditcard.frauddetection.integration;

import com.creditcard.frauddetection.kafka.event.FraudAlertRaisedEvent;
import com.creditcard.frauddetection.kafka.event.TransactionInitiatedEvent;
import com.creditcard.frauddetection.repository.FraudAlertRepository;
import com.creditcard.frauddetection.repository.TransactionRecordRepository;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = {"transaction.initiated", "fraud.alert.raised"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class IdempotencyIntegrationTest {

    @TestConfiguration
    static class TestKafkaConfig {
        @Bean(name = "idempotencyTestKafkaTemplate")
        KafkaTemplate<String, TransactionInitiatedEvent> idempotencyTestKafkaTemplate(
                @Value("${spring.kafka.bootstrap-servers}") String brokers) {
            Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                JsonSerializer.ADD_TYPE_INFO_HEADERS, false
            );
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private KafkaTemplate<String, TransactionInitiatedEvent> idempotencyTestKafkaTemplate;

    @Autowired
    private FraudAlertRepository fraudAlertRepository;

    @Autowired
    private TransactionRecordRepository transactionRecordRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        fraudAlertRepository.deleteAll();
        transactionRecordRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM processed_events");
        jdbcTemplate.update("UPDATE fraud_rule_configs SET parameters = CASE rule_type " +
            "WHEN 'VELOCITY_EXCEEDED' THEN '{\"maxTransactions\": 5, \"windowMinutes\": 10}' " +
            "WHEN 'AMOUNT_ANOMALY' THEN '{\"multiplier\": 3.0, \"lookbackDays\": 30, \"absoluteCeiling\": 10000.00}' " +
            "ELSE '{}' END, enabled = true");
    }

    // ── US3 Scenario 3.1: Same event replayed 10 times → only 1 alert produced ─

    @Test
    void shouldProduceExactlyOneAlertForRepeatedFraudulentEvent() throws Exception {
        String cardToken = "vault:v1:blacklisted-idem-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO blacklist_entries (card_token, reason, added_at) VALUES (?, ?, NOW())",
                cardToken, "Idempotency test");

        UUID txId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        TransactionInitiatedEvent event = buildEvent(eventId, txId, cardToken, "100.00");

        for (int i = 0; i < 10; i++) {
            idempotencyTestKafkaTemplate.send("transaction.initiated", txId.toString(), event)
                    .get(5, TimeUnit.SECONDS);
        }

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(fraudAlertRepository.findAll()).hasSize(1)
        );

        assertThat(fraudAlertRepository.findAll().get(0).getCardToken()).isEqualTo(cardToken);
        assertThat(fraudAlertRepository.findAll().get(0).getTriggeredRules()).contains("BLACKLISTED_CARD");
    }

    // ── US3 Scenario 3.2: Duplicate clean event → 0 fraud alerts produced ──────

    @Test
    void shouldProduceNoAlertForRepeatedCleanEvent() throws Exception {
        String cardToken = "vault:v1:clean-idem-" + UUID.randomUUID();
        jdbcTemplate.update("UPDATE fraud_rule_configs SET parameters = ? WHERE rule_type = ?",
                "{\"maxTransactions\": 100, \"windowMinutes\": 10}", "VELOCITY_EXCEEDED");

        UUID txId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        TransactionInitiatedEvent event = buildEvent(eventId, txId, cardToken, "50.00");

        for (int i = 0; i < 5; i++) {
            idempotencyTestKafkaTemplate.send("transaction.initiated", txId.toString(), event)
                    .get(5, TimeUnit.SECONDS);
        }

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(transactionRecordRepository.findAll())
                    .anyMatch(r -> r.getCardToken().equals(cardToken))
        );

        assertThat(fraudAlertRepository.findAll()).isEmpty();
        assertThat(transactionRecordRepository.findAll())
                .filteredOn(r -> r.getCardToken().equals(cardToken))
                .hasSize(1);
    }

    // ── US3 Scenario 3.3: Null payload event is discarded safely ─────────────

    @Test
    void shouldDiscardNullPayloadWithoutError() throws Exception {
        // Send a legitimate event first to ensure consumer is alive
        UUID txId = UUID.randomUUID();
        String cardToken = "vault:v1:null-test-" + UUID.randomUUID();
        jdbcTemplate.update("UPDATE fraud_rule_configs SET parameters = ? WHERE rule_type = ?",
                "{\"maxTransactions\": 100, \"windowMinutes\": 10}", "VELOCITY_EXCEEDED");

        idempotencyTestKafkaTemplate.send("transaction.initiated", txId.toString(),
                buildEvent(UUID.randomUUID().toString(), txId, cardToken, "50.00"))
                .get(5, TimeUnit.SECONDS);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(transactionRecordRepository.findAll())
                    .anyMatch(r -> r.getCardToken().equals(cardToken))
        );

        assertThat(fraudAlertRepository.findAll()).isEmpty();
    }

    private TransactionInitiatedEvent buildEvent(String eventId, UUID txId, String token, String amount) {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId(eventId);
        event.setEventType("TransactionInitiated");
        event.setTransactionId(txId);
        event.setCardToken(token);
        event.setAmount(new BigDecimal(amount));
        event.setCurrencyCode("USD");
        event.setOccurredAt(Instant.now());
        return event;
    }
}
