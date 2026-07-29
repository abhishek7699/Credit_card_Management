package com.creditcard.settlement.scheduler;

import com.creditcard.settlement.domain.SettlementBatch;
import com.creditcard.settlement.exception.BatchAlreadyRunningException;
import com.creditcard.settlement.service.SettlementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettlementSchedulerTest {

    @Mock
    private SettlementService settlementService;

    @InjectMocks
    private SettlementScheduler scheduler;

    @Test
    void triggerDailySettlement_success_callsLaunchJobWithScheduler() {
        SettlementBatch batch = new SettlementBatch();
        batch.setBatchId("batch-abc");
        when(settlementService.launchJob("SCHEDULER")).thenReturn(batch);

        scheduler.triggerDailySettlement();

        verify(settlementService).launchJob("SCHEDULER");
    }

    @Test
    void triggerDailySettlement_serviceThrows_exceptionIsCaughtAndNotRethrown() {
        doThrow(new BatchAlreadyRunningException("already-running"))
                .when(settlementService).launchJob("SCHEDULER");

        scheduler.triggerDailySettlement();

        verify(settlementService).launchJob("SCHEDULER");
    }

    @Test
    void triggerDailySettlement_runtimeException_exceptionIsCaughtAndNotRethrown() {
        doThrow(new RuntimeException("job failure"))
                .when(settlementService).launchJob("SCHEDULER");

        scheduler.triggerDailySettlement();

        verify(settlementService).launchJob("SCHEDULER");
    }
}
