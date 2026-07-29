package com.creditcard.settlement.scheduler;

import com.creditcard.settlement.service.SettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(SettlementScheduler.class);

    private final SettlementService settlementService;

    public SettlementScheduler(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @Scheduled(cron = "${settlement.batch.cron}", zone = "UTC")
    public void triggerDailySettlement() {
        try {
            log.info("Starting scheduled end-of-day settlement batch");
            settlementService.launchJob("SCHEDULER");
            log.info("Scheduled settlement batch completed successfully");
        } catch (Exception e) {
            log.error("Scheduled settlement batch failed: {}", e.getMessage(), e);
        }
    }
}
