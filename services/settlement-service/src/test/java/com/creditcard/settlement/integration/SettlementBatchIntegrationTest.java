package com.creditcard.settlement.integration;

import com.creditcard.settlement.domain.MerchantSettlement;
import com.creditcard.settlement.domain.SettlementTransaction;
import com.creditcard.settlement.kafka.event.TransactionAuthorizedEvent;
import com.creditcard.settlement.repository.MerchantSettlementRepository;
import com.creditcard.settlement.repository.ProcessedEventRepository;
import com.creditcard.settlement.repository.SettlementBatchRepository;
import com.creditcard.settlement.repository.SettlementTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
class SettlementBatchIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

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
    private SettlementTransactionRepository txRepository;

    @Autowired
    private MerchantSettlementRepository merchantRepo;

    @Autowired
    private SettlementBatchRepository batchRepo;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @BeforeEach
    void setUp() {
        jobRepositoryTestUtils.removeJobExecutions();
        processedEventRepository.deleteAll();
        merchantRepo.deleteAll();
        batchRepo.deleteAll();
        txRepository.deleteAll();
    }

    // ── Scenario 1.1: 5 txns across 3 merchants → all SETTLED with correct aggregates ──

    @Test
    void scenario_1_1_fiveTransactionsThreeMerchants_allSettledCorrectAggregates() throws Exception {
        String batchId = UUID.randomUUID().toString();

        publishEvent("evt-1", UUID.randomUUID(), "merchant-A", "100.00");
        publishEvent("evt-2", UUID.randomUUID(), "merchant-A", "200.00");
        publishEvent("evt-3", UUID.randomUUID(), "merchant-B", "150.00");
        publishEvent("evt-4", UUID.randomUUID(), "merchant-B", "50.00");
        publishEvent("evt-5", UUID.randomUUID(), "merchant-C", "300.00");

        await().atMost(30, SECONDS).until(() -> txRepository.count() == 5);

        JobExecution execution = jobLauncherTestUtils.launchJob(buildParams(batchId));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<SettlementTransaction> settled = txRepository.findByStatus("SETTLED");
        assertThat(settled).hasSize(5);
        settled.forEach(tx -> {
            assertThat(tx.getBatchId()).isEqualTo(batchId);
            assertThat(tx.getSettlementFee()).isNotNull();
            assertThat(tx.getNetAmount()).isNotNull();
        });

        List<MerchantSettlement> settlements = merchantRepo.findAll();
        assertThat(settlements).hasSize(3);

        MerchantSettlement merchantA = settlements.stream()
                .filter(s -> "merchant-A".equals(s.getMerchantId())).findFirst().orElseThrow();
        assertThat(merchantA.getTransactionCount()).isEqualTo(2);
        assertThat(merchantA.getGrossAmount()).isEqualByComparingTo("300.00");
        assertThat(merchantA.getTotalInterchangeFee()).isEqualByComparingTo("6.00");
        assertThat(merchantA.getNetSettlementAmount()).isEqualByComparingTo("294.00");
    }

    // ── Scenario 1.2: no AUTHORIZED transactions → batch COMPLETED with 0 processed ──

    @Test
    void scenario_1_2_noAuthorizedTransactions_batchCompletedZeroProcessed() throws Exception {
        JobExecution execution = jobLauncherTestUtils.launchJob(buildParams(UUID.randomUUID().toString()));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(txRepository.findByStatus("SETTLED")).isEmpty();
        assertThat(merchantRepo.findAll()).isEmpty();
    }

    // ── Scenario 1.3: idempotent re-run → second batch processes 0 transactions ──

    @Test
    void scenario_1_3_idempotentRerun_secondBatchProcessesZero() throws Exception {
        saveTxDirectly(UUID.randomUUID(), "merchant-X", "100.00");
        saveTxDirectly(UUID.randomUUID(), "merchant-X", "200.00");

        JobExecution firstRun = jobLauncherTestUtils.launchJob(buildParams(UUID.randomUUID().toString()));
        assertThat(firstRun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(txRepository.findByStatus("SETTLED")).hasSize(2);

        JobExecution secondRun = jobLauncherTestUtils.launchJob(buildParams(UUID.randomUUID().toString()));
        assertThat(secondRun.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(txRepository.findByStatus("SETTLED")).hasSize(2);
        assertThat(txRepository.findByStatus("AUTHORIZED")).isEmpty();
    }

    // ── Scenario 1.4: event with null merchantId → listener discards, not saved ──

    @Test
    void scenario_1_4_nullMerchantIdEvent_listenerDiscardsIt() throws Exception {
        TransactionAuthorizedEvent event = buildEvent("evt-null-merchant", UUID.randomUUID(), null, "100.00");
        txKafkaTemplate.send("transaction.authorized", "key", event);

        publishEvent("evt-valid", UUID.randomUUID(), "merchant-Z", "50.00");
        await().atMost(15, SECONDS).until(() -> txRepository.count() == 1);

        assertThat(txRepository.findAll()).allSatisfy(tx -> assertThat(tx.getMerchantId()).isNotNull());
    }

    // ── Helpers ──

    private void publishEvent(String eventId, UUID txId, String merchantId, String amount) {
        TransactionAuthorizedEvent event = buildEvent(eventId, txId, merchantId, amount);
        txKafkaTemplate.send("transaction.authorized", txId.toString(), event);
    }

    private TransactionAuthorizedEvent buildEvent(String eventId, UUID txId, String merchantId, String amount) {
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
        return event;
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

    private JobParameters buildParams(String batchId) {
        return new JobParametersBuilder()
                .addString("batchId", batchId)
                .addString("triggeredAt", Instant.now().toString())
                .addString("triggeredBy", "TEST")
                .toJobParameters();
    }
}
