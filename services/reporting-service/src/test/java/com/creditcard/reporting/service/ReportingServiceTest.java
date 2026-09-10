package com.creditcard.reporting.service;

import com.creditcard.reporting.domain.MerchantSettlement;
import com.creditcard.reporting.domain.SettlementBatch;
import com.creditcard.reporting.domain.SettlementTransaction;
import com.creditcard.reporting.exception.InvalidDateRangeException;
import com.creditcard.reporting.exception.ResourceNotFoundException;
import com.creditcard.reporting.repository.MerchantSettlementReportRepository;
import com.creditcard.reporting.repository.SettlementBatchReportRepository;
import com.creditcard.reporting.repository.TransactionReportRepository;
import com.creditcard.reporting.web.dto.MerchantSummaryResponse;
import com.creditcard.reporting.web.dto.PagedResponse;
import com.creditcard.reporting.web.dto.SettlementBatchResponse;
import com.creditcard.reporting.web.dto.TransactionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportingServiceTest {

    @Mock
    private TransactionReportRepository txRepository;

    @Mock
    private MerchantSettlementReportRepository merchantSettlementRepository;

    @Mock
    private SettlementBatchReportRepository batchRepository;

    @InjectMocks
    private ReportingService reportingService;

    // ── getTransactions ────────────────────────────────────────────────────────

    @Test
    void getTransactions_dateRangeExceeds90Days_throwsInvalidDateRangeException() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-01T00:00:00Z"); // 151 days

        assertThatThrownBy(() ->
                reportingService.getTransactions(null, null, from, to, null, PageRequest.of(0, 20)))
                .isInstanceOf(InvalidDateRangeException.class)
                .hasMessageContaining("90 days");
    }

    @Test
    void getTransactions_nullFilters_callsRepositoryAndReturnsPagedResponse() {
        SettlementTransaction tx = new SettlementTransaction();
        tx.setTransactionId("tx-001");
        tx.setCardToken("tok-abc");
        tx.setMaskedCardNumber("4111-****-****-1111");
        tx.setMerchantId("merchant-001");
        tx.setAmount(new BigDecimal("100.00"));
        tx.setCurrencyCode("USD");
        tx.setStatus("SETTLED");
        tx.setAuthorizedAt(Instant.now());

        when(txRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(tx), PageRequest.of(0, 20), 1));

        PagedResponse<TransactionResponse> response = reportingService.getTransactions(
                null, null, null, null, null, PageRequest.of(0, 20));

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getTotalElements()).isEqualTo(1L);
        assertThat(response.getContent().get(0).getTransactionId()).isEqualTo("tx-001");
        verify(txRepository).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    void getTransaction_found_returnsMappedResponse() {
        SettlementTransaction tx = new SettlementTransaction();
        tx.setTransactionId("tx-77");
        tx.setMaskedCardNumber("****-****-****-1111");
        tx.setStatus("AUTHORIZED");
        tx.setMerchantId("merchant-1");
        tx.setAmount(new BigDecimal("99.99"));
        when(txRepository.findById("tx-77")).thenReturn(Optional.of(tx));

        TransactionResponse response = reportingService.getTransaction("tx-77");

        assertThat(response.getTransactionId()).isEqualTo("tx-77");
        assertThat(response.getMaskedCardNumber()).isEqualTo("****-****-****-1111");
        assertThat(response.getStatus()).isEqualTo("AUTHORIZED");
    }

    @Test
    void getTransaction_notFound_throwsResourceNotFoundException() {
        when(txRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportingService.getTransaction("ghost"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void getTransactions_within90Days_doesNotThrow() {
        Instant from = Instant.parse("2026-04-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-01T00:00:00Z"); // 61 days — valid

        when(txRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        PagedResponse<TransactionResponse> response = reportingService.getTransactions(
                null, null, from, to, null, PageRequest.of(0, 20));
        assertThat(response.getContent()).isEmpty();
    }

    @Test
    void getTransactions_fromNonNullToNull_usesDefaultToAndDoesNotThrow() {
        // from != null, to == null → short-circuit branch; default to = now
        Instant from = Instant.parse("2026-05-01T00:00:00Z");

        when(txRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        PagedResponse<TransactionResponse> response = reportingService.getTransactions(
                null, null, from, null, null, PageRequest.of(0, 20));
        assertThat(response.getContent()).isEmpty();
    }

    // ── getMerchantSummary ─────────────────────────────────────────────────────

    @Test
    void getAllMerchantsSummary_pageOffsetBeyondTotal_returnsEmptyContent() {
        MerchantSettlement ms = buildMerchantSettlement("batch-A", "merchant-001",
                5, "500.00", "10.00", "490.00");

        when(merchantSettlementRepository.findBySettlementDateBetween(any(), any()))
                .thenReturn(List.of(ms));

        // page=1 with size=20 → offset=20 > total=1 → empty content
        PagedResponse<MerchantSummaryResponse> response = reportingService.getAllMerchantsSummary(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), PageRequest.of(1, 20));

        assertThat(response.getContent()).isEmpty();
        assertThat(response.getTotalElements()).isEqualTo(1L);
    }

    @Test
    void getMerchantSummary_noResults_returnsZeroSummary() {
        when(merchantSettlementRepository.findByMerchantIdAndSettlementDateBetween(
                any(), any(), any())).thenReturn(List.of());

        LocalDate from = LocalDate.of(2026, 5, 1);
        LocalDate to = LocalDate.of(2026, 5, 31);
        MerchantSummaryResponse summary = reportingService.getMerchantSummary("merchant-new", from, to);

        assertThat(summary.getMerchantId()).isEqualTo("merchant-new");
        assertThat(summary.getTransactionCount()).isZero();
        assertThat(summary.getGrossAmount()).isEqualByComparingTo("0.00");
        assertThat(summary.getTotalInterchangeFee()).isEqualByComparingTo("0.00");
        assertThat(summary.getNetSettlementAmount()).isEqualByComparingTo("0.00");
        assertThat(summary.getBatchCount()).isZero();
    }

    @Test
    void getMerchantSummary_withResults_aggregatesCorrectly() {
        MerchantSettlement ms1 = buildMerchantSettlement("batch-A", "merchant-001",
                3, "300.00", "6.00", "294.00");
        MerchantSettlement ms2 = buildMerchantSettlement("batch-B", "merchant-001",
                2, "200.00", "4.00", "196.00");

        when(merchantSettlementRepository.findByMerchantIdAndSettlementDateBetween(
                any(), any(), any())).thenReturn(List.of(ms1, ms2));

        MerchantSummaryResponse summary = reportingService.getMerchantSummary(
                "merchant-001", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(summary.getTransactionCount()).isEqualTo(5L);
        assertThat(summary.getGrossAmount()).isEqualByComparingTo("500.00");
        assertThat(summary.getTotalInterchangeFee()).isEqualByComparingTo("10.00");
        assertThat(summary.getNetSettlementAmount()).isEqualByComparingTo("490.00");
        assertThat(summary.getBatchCount()).isEqualTo(2L);
    }

    // ── getMerchantSettlement ──────────────────────────────────────────────────

    @Test
    void getMerchantSettlement_notFound_throwsResourceNotFoundException() {
        when(merchantSettlementRepository.findByBatchIdAndMerchantId("batch-X", "ghost"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportingService.getMerchantSettlement("batch-X", "ghost"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("ghost")
                .hasMessageContaining("batch-X");
    }

    @Test
    void getMerchantSettlement_found_returnsMappedResponse() {
        MerchantSettlement ms = buildMerchantSettlement("batch-1", "merchant-001",
                5, "500.00", "10.00", "490.00");
        ms.setId("ms-uuid-001");
        ms.setSettlementDate(LocalDate.of(2026, 6, 13));
        ms.setCurrencyCode("USD");

        when(merchantSettlementRepository.findByBatchIdAndMerchantId("batch-1", "merchant-001"))
                .thenReturn(Optional.of(ms));

        var response = reportingService.getMerchantSettlement("batch-1", "merchant-001");
        assertThat(response.getMerchantId()).isEqualTo("merchant-001");
        assertThat(response.getBatchId()).isEqualTo("batch-1");
        assertThat(response.getTransactionCount()).isEqualTo(5);
    }

    // ── getBatch ──────────────────────────────────────────────────────────────

    @Test
    void getBatch_notFound_throwsResourceNotFoundException() {
        when(batchRepository.findById("nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportingService.getBatch("nonexistent"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("nonexistent");
    }

    @Test
    void getBatch_found_returnsMappedResponse() {
        SettlementBatch batch = new SettlementBatch();
        batch.setBatchId("batch-001");
        batch.setStatus("COMPLETED");
        batch.setTriggeredBy("SCHEDULER");
        batch.setTotalTransactionsProcessed(100);
        batch.setTotalGrossAmount(new BigDecimal("10000.00"));
        batch.setTotalInterchangeFees(new BigDecimal("200.00"));
        batch.setTotalNetAmount(new BigDecimal("9800.00"));
        batch.setStartedAt(Instant.parse("2026-06-13T23:59:00Z"));
        batch.setCompletedAt(Instant.parse("2026-06-13T23:59:47Z"));

        when(batchRepository.findById("batch-001")).thenReturn(Optional.of(batch));

        SettlementBatchResponse response = reportingService.getBatch("batch-001");
        assertThat(response.getBatchId()).isEqualTo("batch-001");
        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.getTotalTransactionsProcessed()).isEqualTo(100);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MerchantSettlement buildMerchantSettlement(String batchId, String merchantId,
            int count, String gross, String fee, String net) {
        MerchantSettlement ms = new MerchantSettlement();
        ms.setBatchId(batchId);
        ms.setMerchantId(merchantId);
        ms.setTransactionCount(count);
        ms.setGrossAmount(new BigDecimal(gross));
        ms.setTotalInterchangeFee(new BigDecimal(fee));
        ms.setNetSettlementAmount(new BigDecimal(net));
        ms.setCurrencyCode("USD");
        ms.setSettlementDate(LocalDate.now());
        return ms;
    }
}
