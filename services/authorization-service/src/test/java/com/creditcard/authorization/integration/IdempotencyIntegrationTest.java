package com.creditcard.authorization.integration;

import com.creditcard.authorization.domain.CardAccount;
import com.creditcard.authorization.domain.CardAccountStatus;
import com.creditcard.authorization.kafka.event.AuthorizationResultEvent;
import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import com.creditcard.authorization.repository.CardAccountRepository;
import com.creditcard.authorization.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
class IdempotencyIntegrationTest {

    @TestConfiguration
    static class TestKafkaConfig {
        @Bean(name = "idempotencyTestTemplate")
        KafkaTemplate<String, TransactionInitiatedEvent> idempotencyTestTemplate(
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
    private KafkaTemplate<String, TransactionInitiatedEvent> idempotencyTestTemplate;

    @Autowired
    private CardAccountRepository cardAccountRepository;

    @Autowired
    private AuthorizationRecordRepository authorizationRecordRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Value("${spring.kafka.bootstrap-servers}")
    private String brokers;

    private KafkaConsumer<String, AuthorizationResultEvent> resultConsumer;

    @BeforeEach
    void setUp() {
        resultConsumer = buildResultConsumer();
        resultConsumer.subscribe(List.of("transaction.status.updated"));
    }

    @AfterEach
    void tearDown() {
        resultConsumer.close();
        authorizationRecordRepository.deleteAll();
        processedEventRepository.deleteAll();
        cardAccountRepository.deleteAll();
    }

    @Test
    void shouldProduceSingleRecordAndDeductOnceForTenDuplicateEvents() throws Exception {
        String token = "vault:v1:idempotent-" + UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        String eventId = "dedup-" + UUID.randomUUID();

        cardAccountRepository.save(buildAccount(token, "1000.00"));

        TransactionInitiatedEvent event = buildEvent(eventId, txId, token, "100.00");
        for (int i = 0; i < 10; i++) {
            idempotencyTestTemplate.send("transaction.initiated", txId.toString(), event).get(5, TimeUnit.SECONDS);
        }

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(authorizationRecordRepository.findByTransactionId(txId)).hasSize(1)
        );

        List<AuthorizationResultEvent> results = drainResultsForTx(txId, 3);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getEventType()).isEqualTo("TransactionAuthorized");

        CardAccount updated = cardAccountRepository.findByCardToken(token).orElseThrow();
        assertThat(updated.getAvailableCredit()).isEqualByComparingTo("900.00");
    }

    @Test
    void shouldHandleConcurrentDuplicatesGracefully() throws Exception {
        String token = "vault:v1:concurrent-" + UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        String eventId = "concurrent-" + UUID.randomUUID();

        cardAccountRepository.save(buildAccount(token, "1000.00"));

        TransactionInitiatedEvent event = buildEvent(eventId, txId, token, "100.00");
        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    idempotencyTestTemplate.send("transaction.initiated", txId.toString(), event).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(authorizationRecordRepository.findByTransactionId(txId)).hasSize(1)
        );

        CardAccount updated = cardAccountRepository.findByCardToken(token).orElseThrow();
        assertThat(updated.getAvailableCredit()).isEqualByComparingTo("900.00");
    }

    @Test
    void shouldNotDoubleDeductOnRedeliveryAfterSuccessfulAuthorization() throws Exception {
        String token = "vault:v1:redelivery-" + UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        String eventId = "redeliver-" + UUID.randomUUID();

        cardAccountRepository.save(buildAccount(token, "1000.00"));

        TransactionInitiatedEvent event = buildEvent(eventId, txId, token, "100.00");
        idempotencyTestTemplate.send("transaction.initiated", txId.toString(), event).get(5, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(authorizationRecordRepository.findByTransactionId(txId)).isNotEmpty()
        );

        // Re-deliver 3 more times
        for (int i = 0; i < 3; i++) {
            idempotencyTestTemplate.send("transaction.initiated", txId.toString(), event).get(5, TimeUnit.SECONDS);
        }

        Thread.sleep(2000);

        assertThat(authorizationRecordRepository.findByTransactionId(txId)).hasSize(1);

        CardAccount updated = cardAccountRepository.findByCardToken(token).orElseThrow();
        assertThat(updated.getAvailableCredit()).isEqualByComparingTo("900.00");
    }

    private List<AuthorizationResultEvent> drainResultsForTx(UUID txId, int waitSeconds) {
        List<AuthorizationResultEvent> results = new ArrayList<>();
        long deadline = System.currentTimeMillis() + (waitSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, AuthorizationResultEvent> records =
                    resultConsumer.poll(Duration.ofMillis(300));
            records.forEach(record -> {
                if (txId.toString().equals(record.key())) {
                    results.add(record.value());
                }
            });
        }
        return results;
    }

    private KafkaConsumer<String, AuthorizationResultEvent> buildResultConsumer() {
        Map<String, Object> props = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
            ConsumerConfig.GROUP_ID_CONFIG, "test-idempotency-consumer-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class,
            JsonDeserializer.TRUSTED_PACKAGES, "com.creditcard.*",
            JsonDeserializer.VALUE_DEFAULT_TYPE, AuthorizationResultEvent.class.getName(),
            JsonDeserializer.USE_TYPE_INFO_HEADERS, false
        );
        return new KafkaConsumer<>(props);
    }

    private CardAccount buildAccount(String token, String credit) {
        CardAccount account = new CardAccount();
        account.setCardToken(token);
        account.setStatus(CardAccountStatus.ACTIVE);
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
