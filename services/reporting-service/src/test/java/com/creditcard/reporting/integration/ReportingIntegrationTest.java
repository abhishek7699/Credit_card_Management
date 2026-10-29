package com.creditcard.reporting.integration;

import com.creditcard.reporting.domain.MerchantSettlement;
import com.creditcard.reporting.domain.SettlementBatch;
import com.creditcard.reporting.domain.SettlementTransaction;
import com.creditcard.reporting.repository.MerchantSettlementReportRepository;
import com.creditcard.reporting.repository.SettlementBatchReportRepository;
import com.creditcard.reporting.repository.TransactionReportRepository;
import com.creditcard.reporting.web.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class ReportingIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15");

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
    private TransactionReportRepository txRepository;

    @Autowired
    private MerchantSettlementReportRepository merchantSettlementRepository;

    @Autowired
    private SettlementBatchReportRepository batchRepository;

    @BeforeEach
    void cleanUp() {
        merchantSettlementRepository.deleteAll();
        batchRepository.deleteAll();
        txRepository.deleteAll();
    }

    // ── US1: Transaction History ───────────────────────────────────────────────

    @Test
    void scenario_1_1_filterByCardToken_returnsOnlyThatCard() {
        saveTx("tx-a1", "tok-card-A", "merchant-001", "100.00", "SETTLED",
                Instant.parse("2026-06-01T10:00:00Z"));
        saveTx("tx-a2", "tok-card-A", "merchant-001", "200.00", "SETTLED",
                Instant.parse("2026-06-02T10:00:00Z"));
        saveTx("tx-b1", "tok-card-B", "merchant-001", "150.00", "SETTLED",
                Instant.parse("2026-06-03T10:00:00Z"));

        String url = base() + "/transactions?cardToken=tok-card-A&size=10"
                + "&from=2026-05-01T00:00:00Z&to=2026-06-30T00:00:00Z";
        ResponseEntity<PagedResponse> response = restTemplate.getForEntity(url, PagedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTotalElements()).isEqualTo(2L);
        assertThat(response.getBody().getContent()).hasSize(2);
    }

    @Test
    void getTransactionById_found_returns200WithMaskedCard() {
        saveTx("tx-byid", "tok-card-Z", "merchant-001", "99.99", "AUTHORIZED",
                Instant.parse("2026-06-01T10:00:00Z"));

        ResponseEntity<TransactionResponse> response =
                restTemplate.getForEntity(base() + "/transactions/tx-byid", TransactionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTransactionId()).isEqualTo("tx-byid");
        assertThat(response.getBody().getMaskedCardNumber()).isEqualTo("4111-****-****-1111");
        assertThat(response.getBody().getStatus()).isEqualTo("AUTHORIZED");
    }

    @Test
    void getTransactionById_notFound_returns404() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(base() + "/transactions/does-not-exist", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("does-not-exist");
    }

    @Test
    void scenario_1_2_filterByMerchantId_returnsOnlyThatMerchant() {
        saveTx("tx-m1a", "tok-1", "merchant-001", "100.00", "AUTHORIZED",
                Instant.parse("2026-06-01T10:00:00Z"));
        saveTx("tx-m1b", "tok-2", "merchant-001", "200.00", "SETTLED",
                Instant.parse("2026-06-02T10:00:00Z"));
        saveTx("tx-m2a", "tok-3", "merchant-002", "150.00", "AUTHORIZED",
                Instant.parse("2026-06-03T10:00:00Z"));
        saveTx("tx-m2b", "tok-4", "merchant-002", "250.00", "SETTLED",
                Instant.parse("2026-06-04T10:00:00Z"));

        String url = base() + "/transactions?merchantId=merchant-001"
                + "&from=2026-05-01T00:00:00Z&to=2026-06-30T00:00:00Z";
        ResponseEntity<PagedResponse> response = restTemplate.getForEntity(url, PagedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTotalElements()).isEqualTo(2L);
    }

    @Test
    void scenario_1_3_filterByDateRange_returnsOnlyWithinWindow() {
        saveTx("tx-jan", "tok-1", "merchant-001", "100.00", "SETTLED",
                Instant.parse("2026-01-01T10:00:00Z"));
        saveTx("tx-mar", "tok-2", "merchant-001", "200.00", "SETTLED",
                Instant.parse("2026-03-15T10:00:00Z"));
        saveTx("tx-jun", "tok-3", "merchant-001", "300.00", "SETTLED",
                Instant.parse("2026-06-01T10:00:00Z"));

        String url = base() + "/transactions?from=2026-02-01T00:00:00Z&to=2026-05-01T00:00:00Z";
        ResponseEntity<PagedResponse> response = restTemplate.getForEntity(url, PagedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTotalElements()).isEqualTo(1L);
    }

    @Test
    void scenario_1_4_filterByStatus_returnsOnlyMatchingStatus() {
        saveTx("tx-auth1", "tok-1", "merchant-001", "100.00", "AUTHORIZED",
                Instant.parse("2026-06-01T10:00:00Z"));
        saveTx("tx-auth2", "tok-2", "merchant-001", "200.00", "AUTHORIZED",
                Instant.parse("2026-06-02T10:00:00Z"));
        saveTx("tx-settled", "tok-3", "merchant-001", "300.00", "SETTLED",
                Instant.parse("2026-06-03T10:00:00Z"));

        String url = base() + "/transactions?status=AUTHORIZED"
                + "&from=2026-05-01T00:00:00Z&to=2026-06-30T00:00:00Z";
        ResponseEntity<PagedResponse> response = restTemplate.getForEntity(url, PagedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTotalElements()).isEqualTo(2L);
    }

    @Test
    void scenario_1_5_combinedFilters_returnOnlyMatchingAll() {
        // matches all 3 conditions
        saveTx("tx-match", "tok-1", "merchant-001", "100.00", "SETTLED",
                Instant.parse("2026-06-15T10:00:00Z"));
        // wrong status
        saveTx("tx-auth", "tok-2", "merchant-001", "200.00", "AUTHORIZED",
                Instant.parse("2026-06-15T10:00:00Z"));
        // wrong merchant
        saveTx("tx-m2", "tok-3", "merchant-002", "150.00", "SETTLED",
                Instant.parse("2026-06-15T10:00:00Z"));

        String url = base() + "/transactions?merchantId=merchant-001&status=SETTLED"
                + "&from=2026-06-01T00:00:00Z&to=2026-06-30T23:59:59Z";
        ResponseEntity<PagedResponse> response = restTemplate.getForEntity(url, PagedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTotalElements()).isEqualTo(1L);
    }

    @Test
    void scenario_1_6_dateRangeExceeds90Days_returns400() {
        String url = base() + "/transactions?from=2026-01-01T00:00:00Z&to=2026-06-01T00:00:00Z";
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("90 days");
    }

    @Test
    void scenario_1_7_emptyResult_returns200WithEmptyList() {
        String url = base() + "/transactions?cardToken=tok-nonexistent"
                + "&from=2026-05-01T00:00:00Z&to=2026-06-30T00:00:00Z";
        ResponseEntity<PagedResponse> response = restTemplate.getForEntity(url, PagedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTotalElements()).isEqualTo(0L);
        assertThat(response.getBody().getContent()).isEmpty();
    }

    @Test
    void scenario_1_8_pagination_returnsCorrectPage() {
        for (int i = 1; i <= 25; i++) {
            saveTx("tx-page-" + i, "tok-" + i, "merchant-001", "100.00", "SETTLED",
                    Instant.parse("2026-06-01T10:00:00Z").plusSeconds(i * 60L));
        }

        String url = base() + "/transactions?merchantId=merchant-001&page=1&size=10"
                + "&from=2026-05-01T00:00:00Z&to=2026-06-30T00:00:00Z";
        ResponseEntity<PagedResponse> response = restTemplate.getForEntity(url, PagedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTotalElements()).isEqualTo(25L);
        assertThat(response.getBody().getTotalPages()).isEqualTo(3);
        assertThat(response.getBody().getContent()).hasSize(10);
        assertThat(response.getBody().getPage()).isEqualTo(1);
    }

    // ── US2: Merchant Summary ──────────────────────────────────────────────────

    @Test
    void scenario_2_1_singleMerchantSummary_correctAggregatedTotals() {
        saveMerchantSettlement("ms-1", "batch-A", "merchant-001", 3,
                "300.00", "6.00", "294.00", LocalDate.of(2026, 6, 10));
        saveMerchantSettlement("ms-2", "batch-B", "merchant-001", 2,
                "200.00", "4.00", "196.00", LocalDate.of(2026, 6, 13));

        String url = base() + "/merchants/merchant-001/summary?from=2026-06-01&to=2026-06-30";
        ResponseEntity<MerchantSummaryResponse> response =
                restTemplate.getForEntity(url, MerchantSummaryResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        MerchantSummaryResponse summary = response.getBody();
        assertThat(summary.getMerchantId()).isEqualTo("merchant-001");
        assertThat(summary.getTransactionCount()).isEqualTo(5L);
        assertThat(summary.getGrossAmount()).isEqualByComparingTo("500.00");
        assertThat(summary.getTotalInterchangeFee()).isEqualByComparingTo("10.00");
        assertThat(summary.getNetSettlementAmount()).isEqualByComparingTo("490.00");
        assertThat(summary.getBatchCount()).isEqualTo(2L);
    }

    @Test
    void scenario_2_2_unknownMerchant_returnsZeroSummary() {
        String url = base() + "/merchants/merchant-new/summary?from=2026-06-01&to=2026-06-30";
        ResponseEntity<MerchantSummaryResponse> response =
                restTemplate.getForEntity(url, MerchantSummaryResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        MerchantSummaryResponse summary = response.getBody();
        assertThat(summary.getMerchantId()).isEqualTo("merchant-new");
        assertThat(summary.getTransactionCount()).isZero();
        assertThat(summary.getGrossAmount()).isEqualByComparingTo("0.00");
        assertThat(summary.getBatchCount()).isZero();
    }

    @Test
    void scenario_2_3_allMerchantsSummary_returnsPerMerchantAggregates() {
        saveMerchantSettlement("ms-A", "batch-1", "merchant-A", 5,
                "500.00", "10.00", "490.00", LocalDate.of(2026, 6, 13));
        saveMerchantSettlement("ms-B", "batch-1", "merchant-B", 3,
                "300.00", "6.00", "294.00", LocalDate.of(2026, 6, 13));
        saveMerchantSettlement("ms-C", "batch-1", "merchant-C", 2,
                "200.00", "4.00", "196.00", LocalDate.of(2026, 6, 13));

        String url = base() + "/merchants/summary?from=2026-06-01&to=2026-06-30";
        ResponseEntity<PagedResponse> response = restTemplate.getForEntity(url, PagedResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTotalElements()).isEqualTo(3L);
        assertThat(response.getBody().getContent()).hasSize(3);
    }

    // ── US3: Settlement Reports ────────────────────────────────────────────────

    @Test
    void scenario_3_1_getBatchById_returnsCompletedBatchSummary() {
        SettlementBatch batch = new SettlementBatch();
        batch.setBatchId("batch-complete-001");
        batch.setStatus("COMPLETED");
        batch.setTriggeredBy("SCHEDULER");
        batch.setTotalTransactionsProcessed(100);
        batch.setTotalGrossAmount(new BigDecimal("10000.00"));
        batch.setTotalInterchangeFees(new BigDecimal("200.00"));
        batch.setTotalNetAmount(new BigDecimal("9800.00"));
        batch.setStartedAt(Instant.parse("2026-06-13T23:59:00Z"));
        batch.setCompletedAt(Instant.parse("2026-06-13T23:59:47Z"));
        batchRepository.save(batch);

        String url = base() + "/settlements/batch-complete-001";
        ResponseEntity<SettlementBatchResponse> response =
                restTemplate.getForEntity(url, SettlementBatchResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SettlementBatchResponse body = response.getBody();
        assertThat(body.getBatchId()).isEqualTo("batch-complete-001");
        assertThat(body.getStatus()).isEqualTo("COMPLETED");
        assertThat(body.getTriggeredBy()).isEqualTo("SCHEDULER");
        assertThat(body.getTotalTransactionsProcessed()).isEqualTo(100);
        assertThat(body.getTotalGrossAmount()).isEqualByComparingTo("10000.00");
        assertThat(body.getTotalInterchangeFees()).isEqualByComparingTo("200.00");
        assertThat(body.getTotalNetAmount()).isEqualByComparingTo("9800.00");
        assertThat(body.getStartedAt()).isNotNull();
        assertThat(body.getCompletedAt()).isNotNull();
    }

    @Test
    void scenario_3_2_getMerchantSettlementInBatch_returnsCorrectRecord() {
        saveMerchantSettlement("ms-batch-m", "batch-xyz", "merchant-001", 10,
                "1000.00", "20.00", "980.00", LocalDate.of(2026, 6, 13));

        String url = base() + "/settlements/batch-xyz/merchants/merchant-001";
        ResponseEntity<MerchantSettlementResponse> response =
                restTemplate.getForEntity(url, MerchantSettlementResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        MerchantSettlementResponse body = response.getBody();
        assertThat(body.getMerchantId()).isEqualTo("merchant-001");
        assertThat(body.getBatchId()).isEqualTo("batch-xyz");
        assertThat(body.getTransactionCount()).isEqualTo(10);
        assertThat(body.getGrossAmount()).isEqualByComparingTo("1000.00");
        assertThat(body.getTotalInterchangeFee()).isEqualByComparingTo("20.00");
        assertThat(body.getNetSettlementAmount()).isEqualByComparingTo("980.00");
        assertThat(body.getSettlementDate()).isEqualTo(LocalDate.of(2026, 6, 13));
    }

    @Test
    void scenario_3_3_unknownBatchId_returns404() {
        String url = base() + "/settlements/nonexistent-batch";
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("Settlement batch not found");
        assertThat(response.getBody()).contains("nonexistent-batch");
    }

    @Test
    void scenario_3_4_knownBatchUnknownMerchant_returns404() {
        saveMerchantSettlement("ms-known", "batch-known", "merchant-real", 5,
                "500.00", "10.00", "490.00", LocalDate.of(2026, 6, 13));

        String url = base() + "/settlements/batch-known/merchants/ghost-merchant";
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("ghost-merchant");
        assertThat(response.getBody()).contains("batch-known");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String base() {
        return "http://localhost:" + port + "/internal/reporting";
    }

    private void saveTx(String txId, String cardToken, String merchantId,
                        String amount, String status, Instant authorizedAt) {
        SettlementTransaction tx = new SettlementTransaction();
        tx.setTransactionId(txId);
        tx.setCardToken(cardToken);
        tx.setMaskedCardNumber("4111-****-****-1111");
        tx.setMerchantId(merchantId);
        tx.setAmount(new BigDecimal(amount));
        tx.setCurrencyCode("USD");
        tx.setStatus(status);
        tx.setAuthorizedAt(authorizedAt);
        tx.setCreatedAt(Instant.now());
        txRepository.save(tx);
    }

    private void saveMerchantSettlement(String id, String batchId, String merchantId,
            int count, String gross, String fee, String net, LocalDate settlementDate) {
        MerchantSettlement ms = new MerchantSettlement();
        ms.setId(id);
        ms.setBatchId(batchId);
        ms.setMerchantId(merchantId);
        ms.setTransactionCount(count);
        ms.setGrossAmount(new BigDecimal(gross));
        ms.setTotalInterchangeFee(new BigDecimal(fee));
        ms.setNetSettlementAmount(new BigDecimal(net));
        ms.setCurrencyCode("USD");
        ms.setSettlementDate(settlementDate);
        ms.setCreatedAt(Instant.now());
        merchantSettlementRepository.save(ms);
    }
}
