package com.creditcard.reporting.service;

import com.creditcard.reporting.domain.MerchantSettlement;
import com.creditcard.reporting.domain.SettlementBatch;
import com.creditcard.reporting.domain.SettlementTransaction;
import com.creditcard.reporting.exception.InvalidDateRangeException;
import com.creditcard.reporting.exception.ResourceNotFoundException;
import com.creditcard.reporting.repository.MerchantSettlementReportRepository;
import com.creditcard.reporting.repository.SettlementBatchReportRepository;
import com.creditcard.reporting.repository.TransactionReportRepository;
import com.creditcard.reporting.web.dto.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ReportingService {

    private final TransactionReportRepository txRepository;
    private final MerchantSettlementReportRepository merchantSettlementRepository;
    private final SettlementBatchReportRepository batchRepository;

    public ReportingService(TransactionReportRepository txRepository,
                            MerchantSettlementReportRepository merchantSettlementRepository,
                            SettlementBatchReportRepository batchRepository) {
        this.txRepository = txRepository;
        this.merchantSettlementRepository = merchantSettlementRepository;
        this.batchRepository = batchRepository;
    }

    // ── Transaction History ────────────────────────────────────────────────────

    public PagedResponse<TransactionResponse> getTransactions(
            String cardToken, String merchantId, Instant from, Instant to,
            String status, Pageable pageable) {

        if (from != null && to != null) {
            long days = ChronoUnit.DAYS.between(from, to);
            if (days > 90) {
                throw new InvalidDateRangeException("Date range exceeds maximum of 90 days");
            }
        }

        Instant effectiveTo = (to != null) ? to : Instant.now();
        Instant effectiveFrom = (from != null) ? from : effectiveTo.minus(30, ChronoUnit.DAYS);

        Specification<SettlementTransaction> spec = buildTransactionSpec(
                cardToken, merchantId, effectiveFrom, effectiveTo, status);

        Page<SettlementTransaction> page = txRepository.findAll(spec, pageable);
        return toPagedResponse(page);
    }

    private Specification<SettlementTransaction> buildTransactionSpec(
            String cardToken, String merchantId, Instant from, Instant to, String status) {

        Specification<SettlementTransaction> spec = Specification.where(
                (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("authorizedAt"), from));
        spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("authorizedAt"), to));

        if (cardToken != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("cardToken"), cardToken));
        }
        if (merchantId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("merchantId"), merchantId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        return spec;
    }

    private PagedResponse<TransactionResponse> toPagedResponse(Page<SettlementTransaction> page) {
        List<TransactionResponse> content = page.getContent().stream()
                .map(this::mapToTransactionResponse)
                .collect(Collectors.toList());

        PagedResponse<TransactionResponse> response = new PagedResponse<>();
        response.setContent(content);
        response.setPage(page.getNumber());
        response.setSize(page.getSize());
        response.setTotalElements(page.getTotalElements());
        response.setTotalPages(page.getTotalPages());
        return response;
    }

    private TransactionResponse mapToTransactionResponse(SettlementTransaction tx) {
        TransactionResponse r = new TransactionResponse();
        r.setTransactionId(tx.getTransactionId());
        r.setCardToken(tx.getCardToken());
        r.setMaskedCardNumber(tx.getMaskedCardNumber());
        r.setMerchantId(tx.getMerchantId());
        r.setAmount(tx.getAmount());
        r.setCurrencyCode(tx.getCurrencyCode());
        r.setStatus(tx.getStatus());
        r.setBatchId(tx.getBatchId());
        r.setSettlementFee(tx.getSettlementFee());
        r.setNetAmount(tx.getNetAmount());
        r.setAuthorizedAt(tx.getAuthorizedAt());
        r.setSettledAt(tx.getSettledAt());
        return r;
    }

    // ── Merchant Summary ──────────────────────────────────────────────────────

    public MerchantSummaryResponse getMerchantSummary(String merchantId, LocalDate from, LocalDate to) {
        List<MerchantSettlement> records = merchantSettlementRepository
                .findByMerchantIdAndSettlementDateBetween(merchantId, from, to);
        return aggregateMerchantSummary(merchantId, from, to, records);
    }

    public PagedResponse<MerchantSummaryResponse> getAllMerchantsSummary(
            LocalDate from, LocalDate to, Pageable pageable) {

        List<MerchantSettlement> allRecords = merchantSettlementRepository
                .findBySettlementDateBetween(from, to);

        Map<String, List<MerchantSettlement>> byMerchant = allRecords.stream()
                .collect(Collectors.groupingBy(MerchantSettlement::getMerchantId));

        List<MerchantSummaryResponse> summaries = byMerchant.entrySet().stream()
                .map(e -> aggregateMerchantSummary(e.getKey(), from, to, e.getValue()))
                .sorted(Comparator.comparing(MerchantSummaryResponse::getGrossAmount).reversed())
                .collect(Collectors.toList());

        int total = summaries.size();
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), total);
        List<MerchantSummaryResponse> pageContent = (start >= total) ? List.of() : summaries.subList(start, end);

        PagedResponse<MerchantSummaryResponse> response = new PagedResponse<>();
        response.setContent(pageContent);
        response.setPage(pageable.getPageNumber());
        response.setSize(pageable.getPageSize());
        response.setTotalElements(total);
        response.setTotalPages((int) Math.ceil((double) total / pageable.getPageSize()));
        return response;
    }

    private MerchantSummaryResponse aggregateMerchantSummary(
            String merchantId, LocalDate from, LocalDate to, List<MerchantSettlement> records) {

        long txCount = records.stream().mapToLong(MerchantSettlement::getTransactionCount).sum();
        BigDecimal gross = records.stream()
                .map(MerchantSettlement::getGrossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fee = records.stream()
                .map(MerchantSettlement::getTotalInterchangeFee)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal net = records.stream()
                .map(MerchantSettlement::getNetSettlementAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String currency = records.isEmpty() ? "USD" : records.get(0).getCurrencyCode();

        MerchantSummaryResponse r = new MerchantSummaryResponse();
        r.setMerchantId(merchantId);
        r.setTransactionCount(txCount);
        r.setGrossAmount(gross);
        r.setTotalInterchangeFee(fee);
        r.setNetSettlementAmount(net);
        r.setCurrencyCode(currency);
        r.setBatchCount(records.size());
        r.setFrom(from);
        r.setTo(to);
        return r;
    }

    // ── Merchant Settlement (per batch) ───────────────────────────────────────

    public MerchantSettlementResponse getMerchantSettlement(String batchId, String merchantId) {
        MerchantSettlement ms = merchantSettlementRepository
                .findByBatchIdAndMerchantId(batchId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No settlement found for merchantId=" + merchantId + " in batchId=" + batchId));
        return mapToMerchantSettlementResponse(ms);
    }

    private MerchantSettlementResponse mapToMerchantSettlementResponse(MerchantSettlement ms) {
        MerchantSettlementResponse r = new MerchantSettlementResponse();
        r.setId(ms.getId());
        r.setBatchId(ms.getBatchId());
        r.setMerchantId(ms.getMerchantId());
        r.setTransactionCount(ms.getTransactionCount());
        r.setGrossAmount(ms.getGrossAmount());
        r.setTotalInterchangeFee(ms.getTotalInterchangeFee());
        r.setNetSettlementAmount(ms.getNetSettlementAmount());
        r.setCurrencyCode(ms.getCurrencyCode());
        r.setSettlementDate(ms.getSettlementDate());
        return r;
    }

    // ── Settlement Batch ──────────────────────────────────────────────────────

    public SettlementBatchResponse getBatch(String batchId) {
        SettlementBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("Settlement batch not found: " + batchId));
        return mapToSettlementBatchResponse(batch);
    }

    private SettlementBatchResponse mapToSettlementBatchResponse(SettlementBatch batch) {
        SettlementBatchResponse r = new SettlementBatchResponse();
        r.setBatchId(batch.getBatchId());
        r.setStatus(batch.getStatus());
        r.setTriggeredBy(batch.getTriggeredBy());
        r.setTotalTransactionsProcessed(batch.getTotalTransactionsProcessed());
        r.setTotalGrossAmount(batch.getTotalGrossAmount());
        r.setTotalInterchangeFees(batch.getTotalInterchangeFees());
        r.setTotalNetAmount(batch.getTotalNetAmount());
        r.setStartedAt(batch.getStartedAt());
        r.setCompletedAt(batch.getCompletedAt());
        r.setFailureReason(batch.getFailureReason());
        return r;
    }
}
