package com.creditcard.settlement.web;

import com.creditcard.settlement.domain.MerchantSettlement;
import com.creditcard.settlement.domain.SettlementBatch;
import com.creditcard.settlement.service.SettlementService;
import com.creditcard.settlement.web.dto.BatchTriggerResponse;
import com.creditcard.settlement.web.dto.MerchantSettlementResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/settlement")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @PostMapping("/trigger")
    public ResponseEntity<BatchTriggerResponse> triggerSettlement() {
        SettlementBatch batch = settlementService.launchJob("MANUAL");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toBatchResponse(batch));
    }

    @GetMapping("/batches/{batchId}")
    public ResponseEntity<BatchTriggerResponse> getBatch(@PathVariable String batchId) {
        SettlementBatch batch = settlementService.findBatch(batchId);
        return ResponseEntity.ok(toBatchResponse(batch));
    }

    @GetMapping("/reports/{merchantId}/{batchId}")
    public ResponseEntity<MerchantSettlementResponse> getMerchantReport(
            @PathVariable String merchantId, @PathVariable String batchId) {
        MerchantSettlement ms = settlementService.findMerchantSettlement(merchantId, batchId);
        return ResponseEntity.ok(toMerchantResponse(ms));
    }

    private BatchTriggerResponse toBatchResponse(SettlementBatch batch) {
        BatchTriggerResponse response = new BatchTriggerResponse();
        response.setBatchId(batch.getBatchId());
        response.setStatus(batch.getStatus());
        response.setTriggeredBy(batch.getTriggeredBy());
        response.setTotalTransactionsProcessed(batch.getTotalTransactionsProcessed());
        response.setTotalGrossAmount(batch.getTotalGrossAmount());
        response.setTotalInterchangeFees(batch.getTotalInterchangeFees());
        response.setTotalNetAmount(batch.getTotalNetAmount());
        response.setStartedAt(batch.getStartedAt());
        response.setCompletedAt(batch.getCompletedAt());
        return response;
    }

    private MerchantSettlementResponse toMerchantResponse(MerchantSettlement ms) {
        MerchantSettlementResponse response = new MerchantSettlementResponse();
        response.setMerchantId(ms.getMerchantId());
        response.setBatchId(ms.getBatchId());
        response.setTransactionCount(ms.getTransactionCount());
        response.setGrossAmount(ms.getGrossAmount());
        response.setTotalInterchangeFee(ms.getTotalInterchangeFee());
        response.setNetSettlementAmount(ms.getNetSettlementAmount());
        response.setCurrencyCode(ms.getCurrencyCode());
        response.setSettlementDate(ms.getSettlementDate());
        return response;
    }
}
