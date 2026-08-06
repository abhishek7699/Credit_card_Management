package com.creditcard.settlement.service;

import com.creditcard.settlement.domain.MerchantSettlement;
import com.creditcard.settlement.domain.SettlementBatch;
import com.creditcard.settlement.exception.BatchAlreadyRunningException;
import com.creditcard.settlement.exception.MerchantSettlementNotFoundException;
import com.creditcard.settlement.repository.MerchantSettlementRepository;
import com.creditcard.settlement.repository.SettlementBatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final JobLauncher jobLauncher;
    private final Job settlementJob;
    private final SettlementBatchRepository settlementBatchRepository;
    private final MerchantSettlementRepository merchantSettlementRepository;

    public SettlementService(JobLauncher jobLauncher,
                              Job settlementJob,
                              SettlementBatchRepository settlementBatchRepository,
                              MerchantSettlementRepository merchantSettlementRepository) {
        this.jobLauncher = jobLauncher;
        this.settlementJob = settlementJob;
        this.settlementBatchRepository = settlementBatchRepository;
        this.merchantSettlementRepository = merchantSettlementRepository;
    }

    public SettlementBatch launchJob(String triggeredBy) {
        settlementBatchRepository.findFirstByStatusOrderByStartedAtDesc("RUNNING")
                .ifPresent(running -> {
                    throw new BatchAlreadyRunningException(running.getBatchId());
                });

        String batchId = UUID.randomUUID().toString();
        SettlementBatch batch = new SettlementBatch();
        batch.setBatchId(batchId);
        batch.setTriggeredBy(triggeredBy);
        batch = settlementBatchRepository.save(batch);

        JobParameters params = new JobParametersBuilder()
                .addString("batchId", batchId)
                .addString("triggeredAt", Instant.now().toString())
                .addString("triggeredBy", triggeredBy)
                .toJobParameters();

        try {
            var execution = jobLauncher.run(settlementJob, params);
            batch.setStatus(execution.getExitStatus().getExitCode().equals("COMPLETED") ? "COMPLETED" : "FAILED");
            if (!"COMPLETED".equals(batch.getStatus())) {
                batch.setFailureReason("Batch exited with: " + execution.getExitStatus().getExitCode());
            }
        } catch (Exception e) {
            log.error("Settlement job failed for batchId={}", batchId, e);
            batch.setStatus("FAILED");
            batch.setFailureReason(e.getMessage());
            settlementBatchRepository.save(batch);
            throw new RuntimeException("Settlement job failed", e);
        }

        batch.setCompletedAt(Instant.now());
        return settlementBatchRepository.save(batch);
    }

    public SettlementBatch findBatch(String batchId) {
        return settlementBatchRepository.findById(batchId)
                .orElseThrow(() -> new NoSuchElementException("Batch not found: " + batchId));
    }

    public MerchantSettlement findMerchantSettlement(String merchantId, String batchId) {
        return merchantSettlementRepository.findByBatchIdAndMerchantId(batchId, merchantId)
                .orElseThrow(() -> new MerchantSettlementNotFoundException(merchantId, batchId));
    }
}
