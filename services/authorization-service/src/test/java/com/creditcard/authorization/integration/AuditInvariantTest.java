package com.creditcard.authorization.integration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.creditcard.authorization.domain.AuthorizationRecord;
import com.creditcard.authorization.domain.CardAccount;
import com.creditcard.authorization.domain.CardAccountStatus;
import com.creditcard.authorization.kafka.event.AuthorizationResultEvent;
import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import com.creditcard.authorization.repository.CardAccountRepository;
import com.creditcard.authorization.repository.ProcessedEventRepository;
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
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = {"transaction.initiated", "transaction.status.updated"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class AuditInvariantTest {

    @TestConfiguration
    static class TestKafkaConfig {
        @Bean(name = "auditTestTemplate")
        KafkaTemplate<String, TransactionInitiatedEvent> auditTestTemplate(
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

    private static final Pattern RAW_PAN_PATTERN = Pattern.compile("\\d{13,19}");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private KafkaTemplate<String, TransactionInitiatedEvent> auditTestTemplate;

    @Autowired
    private CardAccountRepository cardAccountRepository;

    @Autowired
    private AuthorizationRecordRepository authorizationRecordRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Value("${spring.kafka.bootstrap-servers}")
    private String brokers;

    private KafkaConsumer<String, AuthorizationResultEvent> resultConsumer;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        resultConsumer = buildResultConsumer();
        resultConsumer.subscribe(List.of("transaction.status.updated"));

        logAppender = new ListAppender<>();
        logAppender.start();
        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logAppender);
        resultConsumer.close();
        authorizationRecordRepository.deleteAll();
        processedEventRepository.deleteAll();
        cardAccountRepository.deleteAll();
    }

    @Test
    void shouldPersistAllRequiredFieldsAndNeverLogRawPan() throws Exception {
        String activeToken = "vault:v1:audit-active-" + UUID.randomUUID();
        String blockedToken = "vault:v1:audit-blocked-" + UUID.randomUUID();

        cardAccountRepository.save(buildAccount(activeToken, CardAccountStatus.ACTIVE, "500.00"));
        cardAccountRepository.save(buildAccount(blockedToken, CardAccountStatus.BLOCKED, "1000.00"));

        UUID authorizedTxId = UUID.randomUUID();
        UUID declinedTxId = UUID.randomUUID();

        auditTestTemplate.send("transaction.initiated", authorizedTxId.toString(),
                buildEvent(UUID.randomUUID().toString(), authorizedTxId, activeToken, "100.00"))
                .get(5, TimeUnit.SECONDS);

        auditTestTemplate.send("transaction.initiated", declinedTxId.toString(),
                buildEvent(UUID.randomUUID().toString(), declinedTxId, blockedToken, "100.00"))
                .get(5, TimeUnit.SECONDS);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(authorizationRecordRepository.findByTransactionId(authorizedTxId)).hasSize(1);
            assertThat(authorizationRecordRepository.findByTransactionId(declinedTxId)).hasSize(1);
        });

        // Verify all required fields on each record
        AuthorizationRecord authorizedRecord = authorizationRecordRepository
                .findByTransactionId(authorizedTxId).get(0);
        assertThat(authorizedRecord.getTransactionId()).isNotNull();
        assertThat(authorizedRecord.getCardToken()).isNotEmpty();
        assertThat(authorizedRecord.getRequestedAmount()).isNotNull();
        assertThat(authorizedRecord.getCurrencyCode()).isNotEmpty();
        assertThat(authorizedRecord.getOutcome()).isNotNull();
        assertThat(authorizedRecord.getDecidedAt()).isNotNull();
        assertThat(authorizedRecord.getReasonCode()).isNull();

        AuthorizationRecord declinedRecord = authorizationRecordRepository
                .findByTransactionId(declinedTxId).get(0);
        assertThat(declinedRecord.getReasonCode()).isNotNull();

        // Assert no log message contains a raw 13-19 digit PAN sequence
        List<ILoggingEvent> logEvents = logAppender.list;
        for (ILoggingEvent logEvent : logEvents) {
            String message = logEvent.getFormattedMessage();
            assertThat(RAW_PAN_PATTERN.matcher(message).find())
                    .as("Log message should not contain raw PAN digits: " + message)
                    .isFalse();
        }

        // Card tokens in logs must be opaque vault format (not numeric PANs)
        logEvents.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(msg -> msg.contains("vault:"))
                .forEach(msg -> assertThat(msg).doesNotMatch(".*\\d{13,19}.*"));
    }

    private AuthorizationResultEvent pollForResult(UUID txId) {
        ConsumerRecords<String, AuthorizationResultEvent> records =
                resultConsumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, AuthorizationResultEvent> record : records) {
            if (txId.toString().equals(record.key())) {
                return record.value();
            }
        }
        return null;
    }

    private KafkaConsumer<String, AuthorizationResultEvent> buildResultConsumer() {
        Map<String, Object> props = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
            ConsumerConfig.GROUP_ID_CONFIG, "test-audit-consumer-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class,
            JsonDeserializer.TRUSTED_PACKAGES, "com.creditcard.*",
            JsonDeserializer.VALUE_DEFAULT_TYPE, AuthorizationResultEvent.class.getName(),
            JsonDeserializer.USE_TYPE_INFO_HEADERS, false
        );
        return new KafkaConsumer<>(props);
    }

    private CardAccount buildAccount(String token, CardAccountStatus status, String credit) {
        CardAccount account = new CardAccount();
        account.setCardToken(token);
        account.setStatus(status);
        account.setCreditLimit(new BigDecimal("1000.00"));
        account.setAvailableCredit(new BigDecimal(credit));
        account.setCurrencyCode("USD");
        return account;
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
