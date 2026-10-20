package com.creditcard.frauddetection.integration;

import com.creditcard.frauddetection.domain.BlacklistEntry;
import com.creditcard.frauddetection.kafka.event.FraudAlertRaisedEvent;
import com.creditcard.frauddetection.kafka.event.TransactionInitiatedEvent;
import com.creditcard.frauddetection.repository.BlacklistEntryRepository;
import com.creditcard.frauddetection.repository.FraudAlertRepository;
import com.creditcard.frauddetection.repository.FraudRuleConfigRepository;
import com.creditcard.frauddetection.repository.TransactionRecordRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
class FraudDetectionIntegrationTest {

    @TestConfiguration
    static class TestKafkaConfig {
        @Bean
        KafkaTemplate<String, TransactionInitiatedEvent> initiatedKafkaTemplate(
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
    private KafkaTemplate<String, TransactionInitiatedEvent> initiatedKafkaTemplate;

    @Autowired
    private FraudAlertRepository fraudAlertRepository;

    @Autowired
    private TransactionRecordRepository transactionRecordRepository;

    @Autowired
    private BlacklistEntryRepository blacklistEntryRepository;

    @Autowired
    private FraudRuleConfigRepository ruleConfigRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${spring.kafka.bootstrap-servers}")
    private String brokers;

    private KafkaConsumer<String, FraudAlertRaisedEvent> alertConsumer;

    @BeforeEach
    void setUp() {
        alertConsumer = buildAlertConsumer();
        alertConsumer.subscribe(List.of("fraud.alert.raised"));
    }

    @AfterEach
    void tearDown() {
        alertConsumer.close();
        fraudAlertRepository.deleteAll();
        transactionRecordRepository.deleteAll();
        blacklistEntryRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM processed_events");
        jdbcTemplate.update("UPDATE fraud_rule_configs SET parameters = CASE rule_type " +
            "WHEN 'VELOCITY_EXCEEDED' THEN '{\"maxTransactions\": 5, \"windowMinutes\": 10}' " +
            "WHEN 'AMOUNT_ANOMALY' THEN '{\"multiplier\": 3.0, \"lookbackDays\": 30, \"absoluteCeiling\": 10000.00}' " +
            "ELSE '{}' END, enabled = true");
    }

    // ── US1 Scenario 1.1: Velocity rule triggers ──────────────────────────────

    @Test
    void shouldRaiseAlertWhenVelocityThresholdExceeded() throws Exception {
        String cardToken = "vault:v1:velocity-" + UUID.randomUUID();
        jdbcTemplate.update("UPDATE fraud_rule_configs SET parameters = ? WHERE rule_type = ?",
                "{\"maxTransactions\": 3, \"windowMinutes\": 10}", "VELOCITY_EXCEEDED");

        seedTransactionRecords(cardToken, 3, BigDecimal.valueOf(50));

        UUID txId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        initiatedKafkaTemplate.send("transaction.initiated", txId.toString(),
                buildEvent(eventId, txId, cardToken, "50.00")).get(5, TimeUnit.SECONDS);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(fraudAlertRepository.findAll())
                    .anyMatch(a -> a.getCardToken().equals(cardToken)
                            && a.getTriggeredRules().contains("VELOCITY_EXCEEDED"));
        });

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            FraudAlertRaisedEvent alertEvent = pollForAlert(txId);
            assertThat(alertEvent).isNotNull();
            assertThat(alertEvent.getTriggeredRuleTypes()).contains("VELOCITY_EXCEEDED");
            assertThat(alertEvent.getTransactionId()).isEqualTo(txId);
        });
    }

    // ── US1 Scenario 1.2: Amount anomaly triggers ────────────────────────────

    @Test
    void shouldRaiseAlertWhenAmountAnomalyExceedsThreshold() throws Exception {
        String cardToken = "vault:v1:anomaly-" + UUID.randomUUID();
        jdbcTemplate.update("UPDATE fraud_rule_configs SET parameters = ? WHERE rule_type = ?",
                "{\"multiplier\": 3.0, \"lookbackDays\": 30, \"absoluteCeiling\": 10000.00}", "AMOUNT_ANOMALY");

        seedTransactionRecords(cardToken, 5, BigDecimal.valueOf(100));

        UUID txId = UUID.randomUUID();
        initiatedKafkaTemplate.send("transaction.initiated", txId.toString(),
                buildEvent(UUID.randomUUID().toString(), txId, cardToken, "350.00")).get(5, TimeUnit.SECONDS);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(fraudAlertRepository.findAll())
                    .anyMatch(a -> a.getCardToken().equals(cardToken)
                            && a.getTriggeredRules().contains("AMOUNT_ANOMALY"));
        });

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            FraudAlertRaisedEvent alertEvent = pollForAlert(txId);
            assertThat(alertEvent).isNotNull();
            assertThat(alertEvent.getTriggeredRuleTypes()).contains("AMOUNT_ANOMALY");
        });
    }

    // ── US1 Scenario 1.3: Blacklist triggers ─────────────────────────────────

    @Test
    void shouldRaiseAlertWhenCardIsBlacklisted() throws Exception {
        String cardToken = "vault:v1:blacklisted-" + UUID.randomUUID();
        BlacklistEntry entry = new BlacklistEntry();
        entry.setCardToken(cardToken);
        entry.setReason("Test blacklist");
        entry.setAddedAt(Instant.now());
        blacklistEntryRepository.save(entry);

        UUID txId = UUID.randomUUID();
        initiatedKafkaTemplate.send("transaction.initiated", txId.toString(),
                buildEvent(UUID.randomUUID().toString(), txId, cardToken, "100.00")).get(5, TimeUnit.SECONDS);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(fraudAlertRepository.findAll())
                    .anyMatch(a -> a.getCardToken().equals(cardToken)
                            && a.getTriggeredRules().contains("BLACKLISTED_CARD"));
        });

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            FraudAlertRaisedEvent alertEvent = pollForAlert(txId);
            assertThat(alertEvent).isNotNull();
            assertThat(alertEvent.getTriggeredRuleTypes()).contains("BLACKLISTED_CARD");
        });
    }

    // ── US1 Scenario 1.4: Clean transaction ──────────────────────────────────

    @Test
    void shouldProduceNoAlertForCleanTransaction() throws Exception {
        String cardToken = "vault:v1:clean-" + UUID.randomUUID();
        jdbcTemplate.update("UPDATE fraud_rule_configs SET parameters = ? WHERE rule_type = ?",
                "{\"maxTransactions\": 10, \"windowMinutes\": 10}", "VELOCITY_EXCEEDED");

        UUID txId = UUID.randomUUID();
        initiatedKafkaTemplate.send("transaction.initiated", txId.toString(),
                buildEvent(UUID.randomUUID().toString(), txId, cardToken, "50.00")).get(5, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(transactionRecordRepository.findAll())
                    .anyMatch(r -> r.getCardToken().equals(cardToken)));

        assertThat(fraudAlertRepository.findAll())
                .noneMatch(a -> a.getCardToken().equals(cardToken));

        ConsumerRecords<String, FraudAlertRaisedEvent> records =
                alertConsumer.poll(Duration.ofSeconds(2));
        assertThat(records.records("fraud.alert.raised"))
                .noneMatch(r -> txId.toString().equals(r.key()));
    }

    // ── US1 Scenario 1.5: Multiple rules trigger single alert ─────────────────

    @Test
    void shouldProduceSingleAlertWithAllTriggeredRules() throws Exception {
        String cardToken = "vault:v1:multi-" + UUID.randomUUID();
        jdbcTemplate.update("UPDATE fraud_rule_configs SET parameters = ? WHERE rule_type = ?",
                "{\"maxTransactions\": 2, \"windowMinutes\": 10}", "VELOCITY_EXCEEDED");

        BlacklistEntry entry = new BlacklistEntry();
        entry.setCardToken(cardToken);
        entry.setReason("Multi-rule test");
        entry.setAddedAt(Instant.now());
        blacklistEntryRepository.save(entry);

        seedTransactionRecords(cardToken, 2, BigDecimal.valueOf(50));

        UUID txId = UUID.randomUUID();
        initiatedKafkaTemplate.send("transaction.initiated", txId.toString(),
                buildEvent(UUID.randomUUID().toString(), txId, cardToken, "50.00")).get(5, TimeUnit.SECONDS);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            var alerts = fraudAlertRepository.findAll();
            assertThat(alerts).hasSize(1);
            assertThat(alerts.get(0).getTriggeredRules()).contains("VELOCITY_EXCEEDED", "BLACKLISTED_CARD");
        });
    }

    // ── US2 Scenario 2.1: Velocity threshold update ──────────────────────────

    @Test
    void shouldApplyUpdatedVelocityThresholdWithoutRestart() throws Exception {
        String cardToken = "vault:v1:threshold-update-" + UUID.randomUUID();
        jdbcTemplate.update("UPDATE fraud_rule_configs SET parameters = ? WHERE rule_type = ?",
                "{\"maxTransactions\": 5, \"windowMinutes\": 10}", "VELOCITY_EXCEEDED");

        seedTransactionRecords(cardToken, 4, BigDecimal.valueOf(50));

        UUID txId1 = UUID.randomUUID();
        initiatedKafkaTemplate.send("transaction.initiated", txId1.toString(),
                buildEvent(UUID.randomUUID().toString(), txId1, cardToken, "50.00")).get(5, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(transactionRecordRepository.findAll()).anyMatch(r -> r.getCardToken().equals(cardToken)
                    && r.getTransactionId().equals(txId1)));

        assertThat(fraudAlertRepository.findAll())
                .noneMatch(a -> a.getCardToken().equals(cardToken));

        jdbcTemplate.update("UPDATE fraud_rule_configs SET parameters = ? WHERE rule_type = ?",
                "{\"maxTransactions\": 3, \"windowMinutes\": 10}", "VELOCITY_EXCEEDED");

        UUID txId2 = UUID.randomUUID();
        initiatedKafkaTemplate.send("transaction.initiated", txId2.toString(),
                buildEvent(UUID.randomUUID().toString(), txId2, cardToken, "50.00")).get(5, TimeUnit.SECONDS);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(fraudAlertRepository.findAll())
                    .anyMatch(a -> a.getCardToken().equals(cardToken)
                            && a.getTriggeredRules().contains("VELOCITY_EXCEEDED")));
    }

    // ── US2 Scenario 2.2: Blacklist add/remove ───────────────────────────────

    @Test
    void shouldReflectBlacklistChangesWithoutRestart() throws Exception {
        String cardToken = "vault:v1:dynamic-bl-" + UUID.randomUUID();
        jdbcTemplate.update("UPDATE fraud_rule_configs SET parameters = ? WHERE rule_type = ?",
                "{\"maxTransactions\": 100, \"windowMinutes\": 10}", "VELOCITY_EXCEEDED");

        UUID txId1 = UUID.randomUUID();
        initiatedKafkaTemplate.send("transaction.initiated", txId1.toString(),
                buildEvent(UUID.randomUUID().toString(), txId1, cardToken, "50.00")).get(5, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(transactionRecordRepository.findAll()).anyMatch(r -> r.getTransactionId().equals(txId1)));
        assertThat(fraudAlertRepository.findAll()).noneMatch(a -> a.getCardToken().equals(cardToken));

        BlacklistEntry entry = new BlacklistEntry();
        entry.setCardToken(cardToken);
        entry.setReason("Dynamic test");
        entry.setAddedAt(Instant.now());
        blacklistEntryRepository.save(entry);

        UUID txId2 = UUID.randomUUID();
        initiatedKafkaTemplate.send("transaction.initiated", txId2.toString(),
                buildEvent(UUID.randomUUID().toString(), txId2, cardToken, "50.00")).get(5, TimeUnit.SECONDS);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(fraudAlertRepository.findAll())
                    .anyMatch(a -> a.getCardToken().equals(cardToken)
                            && a.getTriggeredRules().contains("BLACKLISTED_CARD")));
    }

    // ── US2 Scenario 2.3: Amount anomaly absolute ceiling ───────────────────

    @Test
    void shouldUseAbsoluteCeilingWhenCardHasNoHistory() throws Exception {
        String cardToken = "vault:v1:no-history-" + UUID.randomUUID();
        jdbcTemplate.update("UPDATE fraud_rule_configs SET parameters = ? WHERE rule_type = ?",
                "{\"multiplier\": 1.0, \"lookbackDays\": 30, \"absoluteCeiling\": 500.00}", "AMOUNT_ANOMALY");

        UUID txId = UUID.randomUUID();
        initiatedKafkaTemplate.send("transaction.initiated", txId.toString(),
                buildEvent(UUID.randomUUID().toString(), txId, cardToken, "600.00")).get(5, TimeUnit.SECONDS);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(fraudAlertRepository.findAll())
                    .anyMatch(a -> a.getCardToken().equals(cardToken)
                            && a.getTriggeredRules().contains("AMOUNT_ANOMALY")));
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    private void seedTransactionRecords(String cardToken, int count, BigDecimal amount) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update(
                "INSERT INTO transaction_records (id, transaction_id, card_token, amount, currency_code, occurred_at) VALUES (gen_random_uuid(), ?, ?, ?, 'USD', ?)",
                UUID.randomUUID(), cardToken, amount,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)));
        }
    }

    private TransactionInitiatedEvent buildEvent(String eventId, UUID txId, String token, String amount) {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId(eventId);
        event.setEventType("TransactionInitiated");
        event.setSourceService("transaction-service");
        event.setOccurredAt(Instant.now());

        TransactionInitiatedEvent.Payload payload = new TransactionInitiatedEvent.Payload();
        payload.setTransactionId(txId);
        payload.setCardToken(token);
        payload.setAmount(new BigDecimal(amount));
        payload.setCurrencyCode("USD");
        event.setPayload(payload);
        return event;
    }

    private FraudAlertRaisedEvent pollForAlert(UUID txId) {
        ConsumerRecords<String, FraudAlertRaisedEvent> records =
                alertConsumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, FraudAlertRaisedEvent> record : records) {
            if (txId.toString().equals(record.key())) {
                return record.value();
            }
        }
        return null;
    }

    private KafkaConsumer<String, FraudAlertRaisedEvent> buildAlertConsumer() {
        Map<String, Object> props = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers != null ? brokers : "localhost:9092",
            ConsumerConfig.GROUP_ID_CONFIG, "test-alert-consumer-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class,
            JsonDeserializer.TRUSTED_PACKAGES, "com.creditcard.*",
            JsonDeserializer.VALUE_DEFAULT_TYPE, FraudAlertRaisedEvent.class.getName(),
            JsonDeserializer.USE_TYPE_INFO_HEADERS, false
        );
        return new KafkaConsumer<>(props);
    }
}
