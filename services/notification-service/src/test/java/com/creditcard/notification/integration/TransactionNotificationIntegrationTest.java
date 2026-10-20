package com.creditcard.notification.integration;

import com.creditcard.notification.domain.CardholderContact;
import com.creditcard.notification.kafka.event.TransactionStatusEvent;
import com.creditcard.notification.repository.CardholderContactRepository;
import com.creditcard.notification.repository.NotificationRecordRepository;
import com.creditcard.notification.repository.ProcessedEventRepository;
import com.creditcard.notification.service.channel.EmailGateway;
import com.creditcard.notification.service.channel.SmsGateway;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = {"transaction.status.updated", "fraud.alert.raised"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class TransactionNotificationIntegrationTest {

    @TestConfiguration
    static class TestKafkaConfig {
        @Bean(name = "txStatusKafkaTemplate")
        KafkaTemplate<String, TransactionStatusEvent> txStatusKafkaTemplate(
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

    @Autowired
    private KafkaTemplate<String, TransactionStatusEvent> txStatusKafkaTemplate;

    @Autowired
    private CardholderContactRepository contactRepo;

    @Autowired
    private NotificationRecordRepository recordRepo;

    @Autowired
    private ProcessedEventRepository processedEventRepo;

    @SpyBean
    private EmailGateway emailGateway;

    @SpyBean
    private SmsGateway smsGateway;

    private static final String CARD_TOKEN = "vault:v1:tok_test";
    private static final String MASKED_NUMBER = "****-****-****-5678";
    private static final String EMAIL = "test@example.com";
    private static final String PHONE = "+14155550000";

    @BeforeEach
    void setUp() {
        recordRepo.deleteAll();
        processedEventRepo.deleteAll();
        contactRepo.deleteAll();
    }

    private void seedContact(boolean txNotificationsEnabled) {
        CardholderContact contact = new CardholderContact();
        contact.setCardToken(CARD_TOKEN);
        contact.setMaskedCardNumber(MASKED_NUMBER);
        contact.setEmail(EMAIL);
        contact.setPhoneNumber(PHONE);
        contact.setTransactionNotificationsEnabled(txNotificationsEnabled);
        contactRepo.save(contact);
    }

    private TransactionStatusEvent buildAuthorizedEvent(String eventId) {
        TransactionStatusEvent e = new TransactionStatusEvent();
        e.setEventId(eventId);
        e.setEventType("TransactionAuthorized");
        e.setSourceService("authorization-service");
        e.setOccurredAt(Instant.now());

        TransactionStatusEvent.Payload payload = new TransactionStatusEvent.Payload();
        payload.setTransactionId(UUID.randomUUID().toString());
        payload.setCardToken(CARD_TOKEN);
        payload.setOutcome("AUTHORIZED");
        e.setPayload(payload);
        return e;
    }

    // Scenario 1.1 — Authorized transaction notification (email + SMS)
    @Test
    void authorizedEvent_producesEmailAndSmsRecords() throws Exception {
        seedContact(true);
        TransactionStatusEvent event = buildAuthorizedEvent(UUID.randomUUID().toString());

        txStatusKafkaTemplate.send("transaction.status.updated", event).get();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<?> records = recordRepo.findAll();
            assertThat(records).hasSize(2);
        });

        var records = recordRepo.findAll();
        assertThat(records).extracting("status").containsOnly("SENT");
        assertThat(records).extracting("channel").containsExactlyInAnyOrder("EMAIL", "SMS");
        assertThat(records).allMatch(r -> r.getContent().contains("5678"));

        verify(emailGateway, times(1)).send(anyString(), anyString(), anyString());
        verify(smsGateway, times(1)).send(anyString(), anyString(), anyString());

        assertThat(processedEventRepo.findAll()).hasSize(1);
    }

    // Scenario 1.2 — Declined transaction notification with reason code
    @Test
    void declinedEvent_contentIncludesReasonCode() throws Exception {
        seedContact(true);
        TransactionStatusEvent event = new TransactionStatusEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("TransactionDeclined");
        event.setOccurredAt(Instant.now());

        TransactionStatusEvent.Payload payload = new TransactionStatusEvent.Payload();
        payload.setTransactionId(UUID.randomUUID().toString());
        payload.setCardToken(CARD_TOKEN);
        payload.setOutcome("DECLINED");
        payload.setReasonCode("INSUFFICIENT_CREDIT");
        event.setPayload(payload);

        txStatusKafkaTemplate.send("transaction.status.updated", event).get();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(recordRepo.findAll()).hasSize(2));

        assertThat(recordRepo.findAll())
                .allMatch(r -> r.getContent().contains("INSUFFICIENT_CREDIT"));
    }

    // Scenario 1.3 — No contact registered → silent acknowledgement
    @Test
    void noContactRegistered_zeroRecordsOneProcessedEvent() throws Exception {
        TransactionStatusEvent event = buildAuthorizedEvent(UUID.randomUUID().toString());

        txStatusKafkaTemplate.send("transaction.status.updated", event).get();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(processedEventRepo.findAll()).hasSize(1));

        assertThat(recordRepo.findAll()).isEmpty();
    }

    // Scenario 1.4 — Transaction notifications disabled → no notification sent
    @Test
    void txNotificationsDisabled_zeroRecords() throws Exception {
        seedContact(false);
        TransactionStatusEvent event = buildAuthorizedEvent(UUID.randomUUID().toString());

        txStatusKafkaTemplate.send("transaction.status.updated", event).get();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(processedEventRepo.findAll()).hasSize(1));

        assertThat(recordRepo.findAll()).isEmpty();
    }

    // Scenario 1.5 — Duplicate event delivery → exactly one notification
    @Test
    void duplicateEvent_exactlyTwoRecordsTotal() throws Exception {
        seedContact(true);
        String eventId = UUID.randomUUID().toString();
        TransactionStatusEvent event = buildAuthorizedEvent(eventId);

        for (int i = 0; i < 5; i++) {
            txStatusKafkaTemplate.send("transaction.status.updated", event).get();
        }

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(processedEventRepo.findAll()).hasSize(1));

        // Give extra time to ensure no duplicates slip through
        Thread.sleep(2000);

        assertThat(recordRepo.findAll()).hasSize(2);
        verify(emailGateway, times(1)).send(anyString(), anyString(), anyString());
        verify(smsGateway, times(1)).send(anyString(), anyString(), anyString());
    }
}
