package com.creditcard.settlement.integration;

import com.creditcard.settlement.domain.SettlementTransaction;
import com.creditcard.settlement.kafka.event.TransactionAuthorizedEvent;
import com.creditcard.settlement.repository.MerchantSettlementRepository;
import com.creditcard.settlement.repository.ProcessedEventRepository;
import com.creditcard.settlement.repository.SettlementBatchRepository;
import com.creditcard.settlement.repository.SettlementTransactionRepository;
import com.creditcard.settlement.web.dto.BatchTriggerResponse;
import com.creditcard.settlement.web.dto.MerchantSettlementResponse;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBatchTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestKafkaConfig.class)
@EmbeddedKafka(
        partitions = 1,
        topics = {"transaction.authorized", "transaction.settled"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class MerchantReportIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

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

    // ── Scenario 3.1: report available after batch with correct aggregated values ──

    @Test
    void scenario_3_1_reportAvailableAfterBatch_correctValues() throws Exception {
        String batchId = UUID.randomUUID().toString();

        saveTxDirectly(UUID.randomUUID(), "merchant-A", "100.00");
        saveTxDirectly(UUID.randomUUID(), "merchant-A", "200.00");
        saveTxDirectly(UUID.randomUUID(), "merchant-B", "150.00");

        JobExecution execution = jobLauncherTestUtils.launchJob(buildParams(batchId));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        String url = "http://localhost:" + port + "/internal/settlement/reports/merchant-A/" + batchId;
        ResponseEntity<MerchantSettlementResponse> response =
                restTemplate.getForEntity(url, MerchantSettlementResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        MerchantSettlementResponse report = response.getBody();
        assertThat(report).isNotNull();
        assertThat(report.getMerchantId()).isEqualTo("merchant-A");
        assertThat(report.getBatchId()).isEqualTo(batchId);
        assertThat(report.getTransactionCount()).isEqualTo(2);
        assertThat(report.getGrossAmount()).isEqualByComparingTo("300.00");
        assertThat(report.getTotalInterchangeFee()).isEqualByComparingTo("6.00");
        assertThat(report.getNetSettlementAmount()).isEqualByComparingTo("294.00");
        assertThat(report.getCurrencyCode()).isEqualTo("USD");
        assertThat(report.getSettlementDate()).isNotNull();
    }

    // ── Scenario 3.2: per-merchant isolation — GET for M1 returns only M1 data ──

    @Test
    void scenario_3_2_perMerchantIsolation_getM1ReturnsOnlyM1() throws Exception {
        String batchId = UUID.randomUUID().toString();

        saveTxDirectly(UUID.randomUUID(), "merchant-X", "500.00");
        saveTxDirectly(UUID.randomUUID(), "merchant-Y", "250.00");

        JobExecution execution = jobLauncherTestUtils.launchJob(buildParams(batchId));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        String urlX = "http://localhost:" + port + "/internal/settlement/reports/merchant-X/" + batchId;
        ResponseEntity<MerchantSettlementResponse> respX =
                restTemplate.getForEntity(urlX, MerchantSettlementResponse.class);
        assertThat(respX.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respX.getBody().getMerchantId()).isEqualTo("merchant-X");
        assertThat(respX.getBody().getGrossAmount()).isEqualByComparingTo("500.00");

        String urlY = "http://localhost:" + port + "/internal/settlement/reports/merchant-Y/" + batchId;
        ResponseEntity<MerchantSettlementResponse> respY =
                restTemplate.getForEntity(urlY, MerchantSettlementResponse.class);
        assertThat(respY.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(respY.getBody().getMerchantId()).isEqualTo("merchant-Y");
        assertThat(respY.getBody().getGrossAmount()).isEqualByComparingTo("250.00");
    }

    // ── Scenario 3.3: 404 for unknown merchant/batch ──

    @Test
    void scenario_3_3_unknownMerchantOrBatch_returns404() throws Exception {
        String url = "http://localhost:" + port
                + "/internal/settlement/reports/ghost-merchant/unknown-batch";
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void scenario_3_3_getBatchNotFound_returns404() {
        String url = "http://localhost:" + port + "/internal/settlement/batches/nonexistent-batch";
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Scenario 3.4: report immutable after re-run — same batchId values unchanged ──

    @Test
    void scenario_3_4_reportImmutableAfterRerun_sameValuesUnchanged() throws Exception {
        String batchId1 = UUID.randomUUID().toString();

        saveTxDirectly(UUID.randomUUID(), "merchant-Z", "100.00");
        saveTxDirectly(UUID.randomUUID(), "merchant-Z", "200.00");

        JobExecution firstRun = jobLauncherTestUtils.launchJob(buildParams(batchId1));
        assertThat(firstRun.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        String url1 = "http://localhost:" + port + "/internal/settlement/reports/merchant-Z/" + batchId1;
        ResponseEntity<MerchantSettlementResponse> firstReport =
                restTemplate.getForEntity(url1, MerchantSettlementResponse.class);
        assertThat(firstReport.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstReport.getBody().getGrossAmount()).isEqualByComparingTo("300.00");

        // Second run — no new AUTHORIZED transactions, so no new merchant settlement for batchId1
        String batchId2 = UUID.randomUUID().toString();
        JobExecution secondRun = jobLauncherTestUtils.launchJob(buildParams(batchId2));
        assertThat(secondRun.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // First batch report still has same values
        ResponseEntity<MerchantSettlementResponse> firstReportAgain =
                restTemplate.getForEntity(url1, MerchantSettlementResponse.class);
        assertThat(firstReportAgain.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstReportAgain.getBody().getGrossAmount()).isEqualByComparingTo("300.00");
        assertThat(firstReportAgain.getBody().getTransactionCount()).isEqualTo(2);
    }

    @Test
    void getBatchStatus_completedBatch_returns200() {
        // SettlementService creates SettlementBatch records; jobLauncherTestUtils bypasses it.
        // Save a batch record directly so findBatch() can locate it.
        com.creditcard.settlement.domain.SettlementBatch batch =
                new com.creditcard.settlement.domain.SettlementBatch();
        String batchId = UUID.randomUUID().toString();
        batch.setBatchId(batchId);
        batch.setTriggeredBy("TEST");
        batch.setStatus("COMPLETED");
        batch.setStartedAt(Instant.now());
        batchRepo.save(batch);

        String url = "http://localhost:" + port + "/internal/settlement/batches/" + batchId;
        ResponseEntity<BatchTriggerResponse> response =
                restTemplate.getForEntity(url, BatchTriggerResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getBatchId()).isEqualTo(batchId);
        assertThat(response.getBody().getStatus()).isEqualTo("COMPLETED");
    }

    // ── Helpers ──

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
