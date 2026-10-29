package com.creditcard.notification.integration;

import com.creditcard.notification.domain.CardholderContact;
import com.creditcard.notification.kafka.event.FraudAlertEvent;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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
class FraudAlertNotificationIntegrationTest {

    @TestConfiguration
    static class TestKafkaConfig {
        @Bean(name = "fraudAlertKafkaTemplate")
        KafkaTemplate<String, FraudAlertEvent> fraudAlertKafkaTemplate(
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
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15-alpine");

    @DynamicPropertySource
    static void postgresProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private KafkaTemplate<String, FraudAlertEvent> fraudAlertKafkaTemplate;

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

    private static final String CARD_TOKEN = "vault:v1:tok_fraud";
    private static final String MASKED_NUMBER = "****-****-****-9999";

    @BeforeEach
    void setUp() {
        recordRepo.deleteAll();
        processedEventRepo.deleteAll();
        contactRepo.deleteAll();
    }

    private void seedContact(boolean txEnabled, boolean hasEmail, boolean hasPhone) {
        CardholderContact contact = new CardholderContact();
        contact.setCardToken(CARD_TOKEN);
        contact.setMaskedCardNumber(MASKED_NUMBER);
        if (hasEmail) contact.setEmail("fraud@example.com");
        if (hasPhone) contact.setPhoneNumber("+14155559999");
        contact.setTransactionNotificationsEnabled(txEnabled);
        contactRepo.save(contact);
    }

    private FraudAlertEvent buildAlert(String eventId, List<String> rules) {
        FraudAlertEvent e = new FraudAlertEvent();
        e.setEventId(eventId);
        e.setEventType("FraudAlertRaised");
        e.setAlertId(UUID.randomUUID().toString());
        e.setTransactionId(UUID.randomUUID().toString());
        e.setCardToken(CARD_TOKEN);
        e.setTriggeredRuleTypes(rules);
        e.setDetectedAt(Instant.now());
        e.setSourceService("fraud-detection-service");
        return e;
    }

    // Scenario 2.1 — Fraud alert delivered even when transaction notifications disabled
    @Test
    void fraudAlert_deliveredEvenWhenTxNotificationsDisabled() throws Exception {
        seedContact(false, true, true);
        FraudAlertEvent event = buildAlert(UUID.randomUUID().toString(), List.of("VELOCITY_EXCEEDED"));

        fraudAlertKafkaTemplate.send("fraud.alert.raised", event).get();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(recordRepo.findAll()).hasSize(2));

        var records = recordRepo.findAll();
        assertThat(records).extracting("status").containsOnly("SENT");
        assertThat(records).extracting("channel").containsExactlyInAnyOrder("EMAIL", "SMS");
        assertThat(records).allMatch(r -> r.getContent().contains("Suspicious activity") || r.getContent().contains("ALERT:"));
        assertThat(records).allMatch(r -> r.getContent().contains("VELOCITY_EXCEEDED"));
        assertThat(records).allMatch(r -> r.getContent().contains("9999"));

        verify(emailGateway, times(1)).send(anyString(), anyString(), anyString());
        verify(smsGateway, times(1)).send(anyString(), anyString(), anyString());
    }

    // Scenario 2.2 — Multiple triggered rules consolidated into one alert per channel
    @Test
    void multipleRules_consolidatedIntoOneAlertPerChannel() throws Exception {
        seedContact(true, true, true);
        FraudAlertEvent event = buildAlert(
                UUID.randomUUID().toString(),
                List.of("VELOCITY_EXCEEDED", "BLACKLISTED_CARD"));

        fraudAlertKafkaTemplate.send("fraud.alert.raised", event).get();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(recordRepo.findAll()).hasSize(2));

        var records = recordRepo.findAll();
        assertThat(records).hasSize(2);
        assertThat(records).allMatch(r ->
                r.getContent().contains("VELOCITY_EXCEEDED") &&
                r.getContent().contains("BLACKLISTED_CARD"));
    }

    // Scenario 2.3 — Fraud alert with SMS only (no email registered)
    @Test
    void smsOnlyContact_onlySmsRecordCreated() throws Exception {
        seedContact(true, false, true);
        FraudAlertEvent event = buildAlert(UUID.randomUUID().toString(), List.of("AMOUNT_ANOMALY"));

        fraudAlertKafkaTemplate.send("fraud.alert.raised", event).get();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(recordRepo.findAll()).hasSize(1));

        var records = recordRepo.findAll();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getChannel()).isEqualTo("SMS");

        verify(emailGateway, never()).send(anyString(), anyString(), anyString());
        verify(smsGateway, times(1)).send(anyString(), anyString(), anyString());
    }

    // Duplicate fraud alert event → exactly one set of records
    @Test
    void duplicateFraudAlert_exactlyTwoRecordsTotal() throws Exception {
        seedContact(true, true, true);
        String eventId = UUID.randomUUID().toString();
        FraudAlertEvent event = buildAlert(eventId, List.of("VELOCITY_EXCEEDED"));

        for (int i = 0; i < 5; i++) {
            fraudAlertKafkaTemplate.send("fraud.alert.raised", event).get();
        }

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(processedEventRepo.findAll()).hasSize(1));

        Thread.sleep(2000);

        assertThat(recordRepo.findAll()).hasSize(2);
        verify(emailGateway, times(1)).send(anyString(), anyString(), anyString());
        verify(smsGateway, times(1)).send(anyString(), anyString(), anyString());
    }
}
