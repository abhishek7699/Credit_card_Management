package com.creditcard.settlement.integration;

import com.creditcard.settlement.domain.SettlementTransaction;
import com.creditcard.settlement.kafka.event.TransactionAuthorizedEvent;
import com.creditcard.settlement.kafka.event.TransactionSettledEvent;
import com.creditcard.settlement.repository.MerchantSettlementRepository;
import com.creditcard.settlement.repository.ProcessedEventRepository;
import com.creditcard.settlement.repository.SettlementBatchRepository;
import com.creditcard.settlement.repository.SettlementTransactionRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBatchTest
@SpringBootTest
@Import(TestKafkaConfig.class)
@EmbeddedKafka(
        partitions = 1,
        topics = {"transaction.authorized", "transaction.settled"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class KafkaEventPublishingIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    @Qualifier("txAuthorizedKafkaTemplate")
    private KafkaTemplate<String, TransactionAuthorizedEvent> txKafkaTemplate;

    @Autowired
    @Qualifier("settledEventsReceived")
    private BlockingQueue<ConsumerRecord<String, TransactionSettledEvent>> settledEvents;

    @Autowired
    private SettlementTransactionRepository txRepository;

    @Autowired
    private MerchantSettlementRepository merchantRepo;

    @Autowired
    private SettlementBatchRepository batchRepo;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @BeforeEach
    void setUp() {
        settledEvents.clear();
        jobRepositoryTestUtils.removeJobExecutions();
        processedEventRepository.deleteAll();
        merchantRepo.deleteAll();
        batchRepo.deleteAll();
        txRepository.deleteAll();
    }

    // ── Scenario 2.1: 5 txns → exactly 5 TransactionSettledEvents with correct payload ──

    @Test
    void scenario_2_1_fiveTransactions_exactlyFiveSettledEventsPublished() throws Exception {
        String batchId = UUID.randomUUID().toString();

        publishEvent("evt-a1", UUID.randomUUID(), "merchant-A", "100.00");
        publishEvent("evt-a2", UUID.randomUUID(), "merchant-A", "200.00");
        publishEvent("evt-b1", UUID.randomUUID(), "merchant-B", "150.00");
        publishEvent("evt-b2", UUID.randomUUID(), "merchant-B", "50.00");
        publishEvent("evt-c1", UUID.randomUUID(), "merchant-C", "300.00");

        await().atMost(30, SECONDS).until(() -> txRepository.count() == 5);

        JobExecution execution = jobLauncherTestUtils.launchJob(buildParams(batchId));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<ConsumerRecord<String, TransactionSettledEvent>> received = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ConsumerRecord<String, TransactionSettledEvent> record =
                    settledEvents.poll(30, TimeUnit.SECONDS);
            assertThat(record).isNotNull();
            received.add(record);
        }
        assertThat(received).hasSize(5);

        received.forEach(record -> {
            TransactionSettledEvent event = record.value();
            assertThat(event.getEventId()).isNotNull();
            assertThat(event.getEventType()).isEqualTo("TransactionSettled");
            assertThat(event.getSourceService()).isEqualTo("settlement-service");
            assertThat(event.getOccurredAt()).isNotNull();
            assertThat(event.getPayload()).isNotNull();

            TransactionSettledEvent.Payload payload = event.getPayload();
            assertThat(payload.getTransactionId()).isNotNull();
            assertThat(payload.getMerchantId()).isNotNull();
            assertThat(payload.getBatchId()).isEqualTo(batchId);
            assertThat(payload.getGrossAmount()).isPositive();
            assertThat(payload.getInterchangeFee()).isNotNull();
            assertThat(payload.getNetAmount()).isNotNull();
            assertThat(payload.getCurrencyCode()).isEqualTo("USD");
            assertThat(payload.getSettlementDate()).isNotNull();
            assertThat(payload.getSettledAt()).isNotNull();
        });

        // verify merchantA's fee is 2% of gross
        received.stream()
                .filter(r -> "merchant-A".equals(r.value().getPayload().getMerchantId()))
                .forEach(r -> {
                    TransactionSettledEvent.Payload p = r.value().getPayload();
                    BigDecimal expectedFee = p.getGrossAmount().multiply(new BigDecimal("0.02"))
                            .setScale(2, java.math.RoundingMode.HALF_UP);
                    assertThat(p.getInterchangeFee()).isEqualByComparingTo(expectedFee);
                    assertThat(p.getNetAmount()).isEqualByComparingTo(
                            p.getGrossAmount().subtract(expectedFee));
                });
    }

    // ── Scenario 2.2: re-run after all settled → 0 additional events ──

    @Test
    void scenario_2_2_reRunAfterAllSettled_zeroAdditionalEvents() throws Exception {
        saveTxDirectly(UUID.randomUUID(), "merchant-X", "100.00");
        saveTxDirectly(UUID.randomUUID(), "merchant-X", "200.00");

        JobExecution firstRun = jobLauncherTestUtils.launchJob(buildParams(UUID.randomUUID().toString()));
        assertThat(firstRun.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Drain first-run events
        List<ConsumerRecord<String, TransactionSettledEvent>> firstBatch = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            ConsumerRecord<String, TransactionSettledEvent> r = settledEvents.poll(20, TimeUnit.SECONDS);
            if (r != null) firstBatch.add(r);
        }
        assertThat(firstBatch).hasSize(2);

        settledEvents.clear();

        JobExecution secondRun = jobLauncherTestUtils.launchJob(buildParams(UUID.randomUUID().toString()));
        assertThat(secondRun.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // Wait briefly; no new events should arrive
        ConsumerRecord<String, TransactionSettledEvent> extra = settledEvents.poll(5, TimeUnit.SECONDS);
        assertThat(extra).isNull();
    }

    // ── Scenario 2.3: batch with 0 AUTHORIZED transactions → 0 events ──

    @Test
    void scenario_2_3_noAuthorizedTransactions_zeroEventsPublished() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(buildParams(UUID.randomUUID().toString()));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        ConsumerRecord<String, TransactionSettledEvent> event = settledEvents.poll(5, TimeUnit.SECONDS);
        assertThat(event).isNull();
    }

    // ── Helpers ──

    private void publishEvent(String eventId, UUID txId, String merchantId, String amount) {
        TransactionAuthorizedEvent event = new TransactionAuthorizedEvent();
        event.setEventId(eventId);
        TransactionAuthorizedEvent.Payload payload = new TransactionAuthorizedEvent.Payload();
        payload.setTransactionId(txId);
        payload.setMerchantId(merchantId);
        payload.setCardToken("tok-" + eventId);
        payload.setMaskedCardNumber("4111-****-****-1111");
        payload.setAmount(new BigDecimal(amount));
        payload.setCurrencyCode("USD");
        payload.setAuthorizedAt(Instant.now());
        event.setPayload(payload);
        txKafkaTemplate.send("transaction.authorized", txId.toString(), event);
    }

    private void saveTxDirectly(UUID txId, String merchantId, String amount) {
        SettlementTransaction tx = new SettlementTransaction();
        tx.setTransactionId(txId);
        tx.setMerchantId(merchantId);
        tx.setCardToken("tok-" + txId);
        tx.setMaskedCardNumber("4111-****-****-1111");
        tx.setAmount(new BigDecimal(amount));
        tx.setCurrencyCode("USD");
        tx.setStatus("AUTHORIZED");
        tx.setAuthorizedAt(Instant.now());
        txRepository.save(tx);
    }

    private org.springframework.batch.core.JobParameters buildParams(String batchId) {
        return new org.springframework.batch.core.JobParametersBuilder()
                .addString("batchId", batchId)
                .addString("triggeredAt", Instant.now().toString())
                .addString("triggeredBy", "TEST")
                .toJobParameters();
    }
}
