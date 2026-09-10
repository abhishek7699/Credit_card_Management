package com.creditcard.authorization.kafka;

import com.creditcard.authorization.domain.CardAccount;
import com.creditcard.authorization.domain.CardAccountStatus;
import com.creditcard.authorization.kafka.event.AuthorizationResultEvent;
import com.creditcard.authorization.kafka.event.TransactionAuthorizedEvent;
import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import com.creditcard.authorization.repository.CardAccountRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = {"transaction.initiated", "transaction.status.updated", "transaction.authorized"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class AuthorizationListenerIntegrationTest {

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
    private CardAccountRepository cardAccountRepository;

    @Autowired
    private AuthorizationRecordRepository authorizationRecordRepository;

    @Value("${spring.kafka.bootstrap-servers}")
    private String brokers;

    private KafkaConsumer<String, AuthorizationResultEvent> resultConsumer;
    private KafkaConsumer<String, TransactionAuthorizedEvent> authorizedConsumer;

    @BeforeEach
    void setUp() {
        resultConsumer = buildConsumer(AuthorizationResultEvent.class);
        resultConsumer.subscribe(List.of("transaction.status.updated"));
        authorizedConsumer = buildConsumer(TransactionAuthorizedEvent.class);
        authorizedConsumer.subscribe(List.of("transaction.authorized"));
    }

    @AfterEach
    void tearDown() {
        resultConsumer.close();
        authorizedConsumer.close();
        authorizationRecordRepository.deleteAll();
        cardAccountRepository.deleteAll();
    }

    @Test
    void shouldAuthorizeAndPublishTransactionAuthorizedWhenCardActiveAndCreditSufficient() throws Exception {
        String token = "vault:v1:token-auth-" + UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        cardAccountRepository.save(buildAccount(token, CardAccountStatus.ACTIVE, "500.00"));

        initiatedKafkaTemplate.send("transaction.initiated", txId.toString(),
                buildEvent(eventId, txId, token, "100.00", "merchant-42")).get(5, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            AuthorizationResultEvent result = poll(resultConsumer, txId);
            assertThat(result).isNotNull();
            assertThat(result.getEventType()).isEqualTo("TransactionAuthorized");
            assertThat(result.getPayload().getTransactionId()).isEqualTo(txId);
        });

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            TransactionAuthorizedEvent authorized = poll(authorizedConsumer, txId);
            assertThat(authorized).isNotNull();
            assertThat(authorized.getEventType()).isEqualTo("TransactionAuthorized");
            assertThat(authorized.getPayload().getMerchantId()).isEqualTo("merchant-42");
            assertThat(authorized.getPayload().getMaskedCardNumber()).isEqualTo("****-****-****-1111");
            assertThat(authorized.getPayload().getAmount()).isEqualByComparingTo("100.00");
            assertThat(authorized.getPayload().getAuthorizedAt()).isNotNull();
        });

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            CardAccount updated = cardAccountRepository.findByCardToken(token).orElseThrow();
            assertThat(updated.getAvailableCredit()).isEqualByComparingTo("400.00");
        });
    }

    @Test
    void shouldDeclineWithoutPublishingTransactionAuthorizedWhenInsufficientCredit() throws Exception {
        String token = "vault:v1:token-decline-" + UUID.randomUUID();
        UUID txId = UUID.randomUUID();

        cardAccountRepository.save(buildAccount(token, CardAccountStatus.ACTIVE, "50.00"));

        initiatedKafkaTemplate.send("transaction.initiated", txId.toString(),
                buildEvent(UUID.randomUUID().toString(), txId, token, "100.00", "m")).get(5, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            AuthorizationResultEvent result = poll(resultConsumer, txId);
            assertThat(result).isNotNull();
            assertThat(result.getEventType()).isEqualTo("TransactionDeclined");
            assertThat(result.getPayload().getReasonCode()).isEqualTo("INSUFFICIENT_CREDIT");
        });

        assertThat(poll(authorizedConsumer, txId)).isNull();
    }

    @Test
    void shouldDeclineWhenCardNotActive() throws Exception {
        String token = "vault:v1:token-blocked-" + UUID.randomUUID();
        UUID txId = UUID.randomUUID();

        cardAccountRepository.save(buildAccount(token, CardAccountStatus.BLOCKED, "1000.00"));

        initiatedKafkaTemplate.send("transaction.initiated", txId.toString(),
                buildEvent(UUID.randomUUID().toString(), txId, token, "100.00", "m")).get(5, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            AuthorizationResultEvent result = poll(resultConsumer, txId);
            assertThat(result).isNotNull();
            assertThat(result.getEventType()).isEqualTo("TransactionDeclined");
            assertThat(result.getPayload().getReasonCode()).isEqualTo("CARD_NOT_ACTIVE");
        });
    }

    @Test
    void shouldDeclineWhenCardTokenUnknown() throws Exception {
        UUID txId = UUID.randomUUID();

        initiatedKafkaTemplate.send("transaction.initiated", txId.toString(),
                buildEvent(UUID.randomUUID().toString(), txId, "vault:v1:unknown-token", "100.00", "m"))
                .get(5, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            AuthorizationResultEvent result = poll(resultConsumer, txId);
            assertThat(result).isNotNull();
            assertThat(result.getEventType()).isEqualTo("TransactionDeclined");
            assertThat(result.getPayload().getReasonCode()).isEqualTo("INVALID_CARD");
        });
    }

    private <T> T poll(KafkaConsumer<String, T> consumer, UUID txId) {
        ConsumerRecords<String, T> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, T> record : records) {
            if (txId.toString().equals(record.key())) {
                return record.value();
            }
        }
        return null;
    }

    private <T> KafkaConsumer<String, T> buildConsumer(Class<T> valueType) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers != null ? brokers : "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.creditcard.*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, valueType.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
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

    private TransactionInitiatedEvent buildEvent(String eventId, UUID txId, String token, String amount, String merchantId) {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId(eventId);
        event.setEventType("TransactionInitiated");
        event.setSourceService("transaction-service");
        event.setOccurredAt(Instant.now());
        TransactionInitiatedEvent.Payload payload = new TransactionInitiatedEvent.Payload();
        payload.setTransactionId(txId);
        payload.setCardToken(token);
        payload.setMaskedCardNumber("****-****-****-1111");
        payload.setCardholderName("Jane Smith");
        payload.setAmount(new BigDecimal(amount));
        payload.setCurrencyCode("USD");
        payload.setMerchantId(merchantId);
        event.setPayload(payload);
        return event;
    }
}
