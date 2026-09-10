package com.creditcard.reporting.web;

import com.creditcard.reporting.service.ReportingService;
import com.creditcard.reporting.web.dto.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;

@RestController
@RequestMapping("/internal/reporting")
@Validated
public class ReportingController {

    private final ReportingService reportingService;

    public ReportingController(ReportingService reportingService) {
        this.reportingService = reportingService;
    }

    // ── US1: Transaction History ───────────────────────────────────────────────

    @GetMapping("/transactions/{id}")
    public ResponseEntity<TransactionResponse> getTransaction(@PathVariable String id) {
        return ResponseEntity.ok(reportingService.getTransaction(id));
    }

    @GetMapping("/transactions")
    public ResponseEntity<PagedResponse<TransactionResponse>> getTransactions(
            @RequestParam(required = false) String cardToken,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        PagedResponse<TransactionResponse> response = reportingService.getTransactions(
                cardToken, merchantId, from, to, status,
                PageRequest.of(page, size, Sort.by("authorizedAt").descending()));
        return ResponseEntity.ok(response);
    }

    // ── US2: Merchant Summary ──────────────────────────────────────────────────

    @GetMapping("/merchants/{merchantId}/summary")
    public ResponseEntity<MerchantSummaryResponse> getMerchantSummary(
            @PathVariable String merchantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        LocalDate effectiveTo = (to != null) ? to : LocalDate.now();
        LocalDate effectiveFrom = (from != null) ? from : effectiveTo.minusDays(30);
        return ResponseEntity.ok(reportingService.getMerchantSummary(merchantId, effectiveFrom, effectiveTo));
    }

    @GetMapping("/merchants/summary")
    public ResponseEntity<PagedResponse<MerchantSummaryResponse>> getAllMerchantsSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        LocalDate effectiveTo = (to != null) ? to : LocalDate.now();
        LocalDate effectiveFrom = (from != null) ? from : effectiveTo.minusDays(30);
        return ResponseEntity.ok(reportingService.getAllMerchantsSummary(
                effectiveFrom, effectiveTo, PageRequest.of(page, size)));
    }

    // ── US3: Settlement Reports ────────────────────────────────────────────────

    @GetMapping("/settlements/{batchId}")
    public ResponseEntity<SettlementBatchResponse> getSettlementBatch(@PathVariable String batchId) {
        return ResponseEntity.ok(reportingService.getBatch(batchId));
    }

    @GetMapping("/settlements/{batchId}/merchants/{merchantId}")
    public ResponseEntity<MerchantSettlementResponse> getMerchantSettlementInBatch(
            @PathVariable String batchId,
            @PathVariable String merchantId) {
        return ResponseEntity.ok(reportingService.getMerchantSettlement(batchId, merchantId));
    }
}
