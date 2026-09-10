package com.creditcard.reporting.web;

import com.creditcard.reporting.exception.InvalidDateRangeException;
import com.creditcard.reporting.exception.ResourceNotFoundException;
import com.creditcard.reporting.service.ReportingService;
import com.creditcard.reporting.web.dto.MerchantSummaryResponse;
import com.creditcard.reporting.web.dto.PagedResponse;
import com.creditcard.reporting.web.dto.SettlementBatchResponse;
import com.creditcard.reporting.web.dto.TransactionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {ReportingController.class, GlobalExceptionHandler.class})
class ReportingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReportingService reportingService;

    // ── GET /internal/reporting/transactions ───────────────────────────────────

    @Test
    void getTransactions_noFilters_returns200WithPagedResponse() throws Exception {
        TransactionResponse tx = new TransactionResponse();
        tx.setTransactionId("tx-001");
        tx.setCardToken("tok-abc");
        tx.setMaskedCardNumber("4111-****-****-1111");
        tx.setMerchantId("merchant-001");
        tx.setAmount(new BigDecimal("100.00"));
        tx.setCurrencyCode("USD");
        tx.setStatus("SETTLED");
        tx.setAuthorizedAt(Instant.parse("2026-06-13T10:00:00Z"));

        PagedResponse<TransactionResponse> paged = new PagedResponse<>();
        paged.setContent(List.of(tx));
        paged.setPage(0);
        paged.setSize(20);
        paged.setTotalElements(1L);
        paged.setTotalPages(1);

        when(reportingService.getTransactions(any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(paged);

        mockMvc.perform(get("/internal/reporting/transactions")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].transactionId").value("tx-001"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void getTransactionById_found_returns200() throws Exception {
        TransactionResponse tx = new TransactionResponse();
        tx.setTransactionId("tx-77");
        tx.setMaskedCardNumber("****-****-****-1111");
        tx.setStatus("AUTHORIZED");

        when(reportingService.getTransaction("tx-77")).thenReturn(tx);

        mockMvc.perform(get("/internal/reporting/transactions/tx-77")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("tx-77"))
                .andExpect(jsonPath("$.maskedCardNumber").value("****-****-****-1111"))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }

    @Test
    void getTransactionById_notFound_returns404() throws Exception {
        when(reportingService.getTransaction("ghost"))
                .thenThrow(new ResourceNotFoundException("Transaction not found: ghost"));

        mockMvc.perform(get("/internal/reporting/transactions/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("ghost")));
    }

    @Test
    void getTransactions_servicethrowsInvalidDateRange_returns400() throws Exception {
        when(reportingService.getTransactions(any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenThrow(new InvalidDateRangeException("Date range exceeds maximum of 90 days"));

        mockMvc.perform(get("/internal/reporting/transactions")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-06-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Date range exceeds maximum of 90 days"));
    }

    @Test
    void getTransactions_pageSizeOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/internal/reporting/transactions")
                        .param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postTransactions_returns405MethodNotAllowed() throws Exception {
        mockMvc.perform(post("/internal/reporting/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    // ── GET /internal/reporting/merchants/{merchantId}/summary ─────────────────

    @Test
    void getMerchantSummary_returns200() throws Exception {
        MerchantSummaryResponse summary = new MerchantSummaryResponse();
        summary.setMerchantId("merchant-001");
        summary.setTransactionCount(5L);
        summary.setGrossAmount(new BigDecimal("500.00"));
        summary.setTotalInterchangeFee(new BigDecimal("10.00"));
        summary.setNetSettlementAmount(new BigDecimal("490.00"));
        summary.setCurrencyCode("USD");
        summary.setBatchCount(2L);
        summary.setFrom(LocalDate.of(2026, 5, 14));
        summary.setTo(LocalDate.of(2026, 6, 13));

        when(reportingService.getMerchantSummary(anyString(), any(), any())).thenReturn(summary);

        mockMvc.perform(get("/internal/reporting/merchants/merchant-001/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value("merchant-001"))
                .andExpect(jsonPath("$.transactionCount").value(5));
    }

    // ── GET /internal/reporting/merchants/summary ──────────────────────────────

    @Test
    void getAllMerchantsSummary_returns200WithPagedResponse() throws Exception {
        MerchantSummaryResponse summary = new MerchantSummaryResponse();
        summary.setMerchantId("merchant-001");
        summary.setGrossAmount(new BigDecimal("1000.00"));

        PagedResponse<MerchantSummaryResponse> paged = new PagedResponse<>();
        paged.setContent(List.of(summary));
        paged.setPage(0);
        paged.setSize(20);
        paged.setTotalElements(1L);
        paged.setTotalPages(1);

        when(reportingService.getAllMerchantsSummary(any(), any(), any(Pageable.class))).thenReturn(paged);

        mockMvc.perform(get("/internal/reporting/merchants/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].merchantId").value("merchant-001"));
    }

    // ── GET /internal/reporting/settlements/{batchId} ─────────────────────────

    @Test
    void getSettlementBatch_found_returns200() throws Exception {
        SettlementBatchResponse batch = new SettlementBatchResponse();
        batch.setBatchId("batch-001");
        batch.setStatus("COMPLETED");
        batch.setTriggeredBy("SCHEDULER");
        batch.setTotalTransactionsProcessed(100);
        batch.setTotalGrossAmount(new BigDecimal("10000.00"));
        batch.setTotalInterchangeFees(new BigDecimal("200.00"));
        batch.setTotalNetAmount(new BigDecimal("9800.00"));
        batch.setStartedAt(Instant.parse("2026-06-13T23:59:00Z"));

        when(reportingService.getBatch("batch-001")).thenReturn(batch);

        mockMvc.perform(get("/internal/reporting/settlements/batch-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("batch-001"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void getSettlementBatch_notFound_returns404() throws Exception {
        when(reportingService.getBatch("missing"))
                .thenThrow(new ResourceNotFoundException("Settlement batch not found: missing"));

        mockMvc.perform(get("/internal/reporting/settlements/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Settlement batch not found: missing"));
    }

    // ── GET /internal/reporting/settlements/{batchId}/merchants/{merchantId} ───

    @Test
    void getMerchantSettlementInBatch_notFound_returns404() throws Exception {
        when(reportingService.getMerchantSettlement(anyString(), anyString()))
                .thenThrow(new ResourceNotFoundException("No settlement found for merchantId=ghost in batchId=batch-001"));

        mockMvc.perform(get("/internal/reporting/settlements/batch-001/merchants/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("ghost")));
    }
}
