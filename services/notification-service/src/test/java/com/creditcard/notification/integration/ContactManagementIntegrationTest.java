package com.creditcard.notification.integration;

import com.creditcard.notification.domain.CardholderContact;
import com.creditcard.notification.kafka.event.FraudAlertEvent;
import com.creditcard.notification.kafka.event.TransactionStatusEvent;
import com.creditcard.notification.repository.CardholderContactRepository;
import com.creditcard.notification.repository.NotificationRecordRepository;
import com.creditcard.notification.repository.ProcessedEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(
    partitions = 1,
    topics = {"transaction.status.updated", "fraud.alert.raised"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class ContactManagementIntegrationTest {

    @TestConfiguration
    static class TestKafkaConfig {
        @Bean(name = "contactTestTxKafkaTemplate")
        KafkaTemplate<String, TransactionStatusEvent> contactTestTxKafkaTemplate(
                @Value("${spring.kafka.bootstrap-servers}") String brokers) {
            Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                JsonSerializer.ADD_TYPE_INFO_HEADERS, false
            );
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }

        @Bean(name = "contactTestFraudKafkaTemplate")
        KafkaTemplate<String, FraudAlertEvent> contactTestFraudKafkaTemplate(
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
    static void postgresProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private KafkaTemplate<String, TransactionStatusEvent> contactTestTxKafkaTemplate;

    @Autowired
    private KafkaTemplate<String, FraudAlertEvent> contactTestFraudKafkaTemplate;

    @Autowired
    private CardholderContactRepository contactRepo;

    @Autowired
    private NotificationRecordRepository recordRepo;

    @Autowired
    private ProcessedEventRepository processedEventRepo;

    @Autowired
    private ObjectMapper objectMapper;

    private String baseUrl() {
        return "http://localhost:" + port + "/api/v1/cardholder-contacts";
    }

    @BeforeEach
    void setUp() {
        recordRepo.deleteAll();
        processedEventRepo.deleteAll();
        contactRepo.deleteAll();
    }

    // Scenario 3.1 — Register new contact → GET returns it → next event uses new address
    @Test
    void registerNewContact_getReturnsDetails() {
        Map<String, Object> payload = Map.of(
            "cardToken", "vault:v1:tok_us3",
            "maskedCardNumber", "****-****-****-3333",
            "email", "new@example.com",
            "phoneNumber", "+14155553333"
        );

        ResponseEntity<Map> postResp = restTemplate.postForEntity(baseUrl(), payload, Map.class);
        assertThat(postResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> getResp = restTemplate.getForEntity(
                baseUrl() + "/vault:v1:tok_us3", Map.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody()).containsEntry("email", "new@example.com");
        assertThat(getResp.getBody()).containsEntry("cardToken", "vault:v1:tok_us3");
    }

    // Scenario 3.2 — Update email → next event uses new email
    @Test
    void updateEmail_nextEventUsesNewEmail() throws Exception {
        // Register initial contact
        Map<String, Object> initial = Map.of(
            "cardToken", "vault:v1:tok_us3b",
            "maskedCardNumber", "****-****-****-4444",
            "email", "old@example.com",
            "phoneNumber", "+14155554444"
        );
        restTemplate.postForEntity(baseUrl(), initial, Map.class);

        // Update email
        Map<String, Object> updated = Map.of(
            "cardToken", "vault:v1:tok_us3b",
            "maskedCardNumber", "****-****-****-4444",
            "email", "updated@example.com",
            "phoneNumber", "+14155554444"
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.exchange(
                baseUrl() + "/vault:v1:tok_us3b",
                HttpMethod.PUT,
                new HttpEntity<>(updated, headers),
                Map.class);

        // Verify GET returns new email
        ResponseEntity<Map> getResp = restTemplate.getForEntity(
                baseUrl() + "/vault:v1:tok_us3b", Map.class);
        assertThat(getResp.getBody()).containsEntry("email", "updated@example.com");

        // Publish event and verify records are created (email recipient uses new address)
        TransactionStatusEvent event = new TransactionStatusEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("TransactionAuthorized");
        event.setTransactionId(UUID.randomUUID().toString());
        event.setCardToken("vault:v1:tok_us3b");
        event.setOccurredAt(Instant.now());

        contactTestTxKafkaTemplate.send("transaction.status.updated", event).get();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(recordRepo.findAll()).hasSize(2));

        // The masked email in recipient should contain the updated domain
        assertThat(recordRepo.findAll())
                .filteredOn(r -> "EMAIL".equals(r.getChannel()))
                .allMatch(r -> r.getRecipientMasked().contains("@example.com"));
    }

    // Scenario 3.3 — Disable tx notifications → only fraud alerts delivered
    @Test
    void disableTxNotifications_onlyFraudAlertsDelivered() throws Exception {
        Map<String, Object> payload = Map.of(
            "cardToken", "vault:v1:tok_us3c",
            "maskedCardNumber", "****-****-****-5555",
            "email", "pref@example.com",
            "phoneNumber", "+14155555555",
            "transactionNotificationsEnabled", false
        );
        restTemplate.postForEntity(baseUrl(), payload, Map.class);

        // Transaction event → 0 records
        TransactionStatusEvent txEvent = new TransactionStatusEvent();
        txEvent.setEventId(UUID.randomUUID().toString());
        txEvent.setEventType("TransactionAuthorized");
        txEvent.setTransactionId(UUID.randomUUID().toString());
        txEvent.setCardToken("vault:v1:tok_us3c");
        txEvent.setOccurredAt(Instant.now());
        contactTestTxKafkaTemplate.send("transaction.status.updated", txEvent).get();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(processedEventRepo.findAll()).hasSize(1));
        assertThat(recordRepo.findAll()).isEmpty();

        // Fraud event → 2 records (EMAIL + SMS)
        FraudAlertEvent fraudEvent = new FraudAlertEvent();
        fraudEvent.setEventId(UUID.randomUUID().toString());
        fraudEvent.setEventType("FraudAlertRaised");
        fraudEvent.setAlertId(UUID.randomUUID().toString());
        fraudEvent.setTransactionId(UUID.randomUUID().toString());
        fraudEvent.setCardToken("vault:v1:tok_us3c");
        fraudEvent.setTriggeredRuleTypes(List.of("VELOCITY_EXCEEDED"));
        fraudEvent.setDetectedAt(Instant.now());
        contactTestFraudKafkaTemplate.send("fraud.alert.raised", fraudEvent).get();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(recordRepo.findAll()).hasSize(2));

        assertThat(recordRepo.findAll()).extracting("status").containsOnly("SENT");
    }
}
