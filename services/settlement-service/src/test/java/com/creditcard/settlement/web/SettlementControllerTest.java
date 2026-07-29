package com.creditcard.settlement.web;

import com.creditcard.settlement.domain.MerchantSettlement;
import com.creditcard.settlement.domain.SettlementBatch;
import com.creditcard.settlement.exception.BatchAlreadyRunningException;
import com.creditcard.settlement.exception.MerchantSettlementNotFoundException;
import com.creditcard.settlement.service.SettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {SettlementController.class, GlobalExceptionHandler.class})
class SettlementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SettlementService settlementService;

    // ── POST /internal/settlement/trigger ──

    @Test
    void trigger_success_returns202WithBatchResponse() throws Exception {
        SettlementBatch batch = buildBatch("COMPLETED");
        when(settlementService.launchJob("MANUAL")).thenReturn(batch);

        mockMvc.perform(post("/internal/settlement/trigger"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.batchId").value(batch.getBatchId()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.triggeredBy").value("MANUAL"));
    }

    @Test
    void trigger_batchAlreadyRunning_returns409() throws Exception {
        when(settlementService.launchJob("MANUAL"))
                .thenThrow(new BatchAlreadyRunningException("running-id"));

        mockMvc.perform(post("/internal/settlement/trigger"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Settlement batch is already running"))
                .andExpect(jsonPath("$.runningBatchId").value("running-id"));
    }

    // ── GET /internal/settlement/batches/{batchId} ──

    @Test
    void getBatch_found_returns200() throws Exception {
        SettlementBatch batch = buildBatch("COMPLETED");
        when(settlementService.findBatch(batch.getBatchId())).thenReturn(batch);

        mockMvc.perform(get("/internal/settlement/batches/{batchId}", batch.getBatchId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batch.getBatchId()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void getBatch_notFound_returns404() throws Exception {
        when(settlementService.findBatch("unknown"))
                .thenThrow(new NoSuchElementException("Batch not found: unknown"));

        mockMvc.perform(get("/internal/settlement/batches/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("unknown")));
    }

    // ── GET /internal/settlement/reports/{merchantId}/{batchId} ──

    @Test
    void getMerchantReport_found_returns200() throws Exception {
        MerchantSettlement ms = buildMerchantSettlement("merchant-A", "batch-001");
        when(settlementService.findMerchantSettlement("merchant-A", "batch-001")).thenReturn(ms);

        mockMvc.perform(get("/internal/settlement/reports/merchant-A/batch-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value("merchant-A"))
                .andExpect(jsonPath("$.batchId").value("batch-001"))
                .andExpect(jsonPath("$.transactionCount").value(3))
                .andExpect(jsonPath("$.grossAmount").value(300.00))
                .andExpect(jsonPath("$.totalInterchangeFee").value(6.00))
                .andExpect(jsonPath("$.netSettlementAmount").value(294.00))
                .andExpect(jsonPath("$.currencyCode").value("USD"));
    }

    @Test
    void getMerchantReport_notFound_returns404() throws Exception {
        when(settlementService.findMerchantSettlement("ghost", "batch-001"))
                .thenThrow(new MerchantSettlementNotFoundException("ghost", "batch-001"));

        mockMvc.perform(get("/internal/settlement/reports/ghost/batch-001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("ghost")));
    }

    // ── Helpers ──

    private SettlementBatch buildBatch(String status) {
        SettlementBatch batch = new SettlementBatch();
        batch.setBatchId(UUID.randomUUID().toString());
        batch.setTriggeredBy("MANUAL");
        batch.setStatus(status);
        batch.setStartedAt(Instant.now());
        batch.setTotalGrossAmount(BigDecimal.ZERO);
        batch.setTotalInterchangeFees(BigDecimal.ZERO);
        batch.setTotalNetAmount(BigDecimal.ZERO);
        return batch;
    }

    private MerchantSettlement buildMerchantSettlement(String merchantId, String batchId) {
        MerchantSettlement ms = new MerchantSettlement();
        ms.setMerchantId(merchantId);
        ms.setBatchId(batchId);
        ms.setTransactionCount(3);
        ms.setGrossAmount(new BigDecimal("300.00"));
        ms.setTotalInterchangeFee(new BigDecimal("6.00"));
        ms.setNetSettlementAmount(new BigDecimal("294.00"));
        ms.setCurrencyCode("USD");
        ms.setSettlementDate(LocalDate.now());
        return ms;
    }
}
