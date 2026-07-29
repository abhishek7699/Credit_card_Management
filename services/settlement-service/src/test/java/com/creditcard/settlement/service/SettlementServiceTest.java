package com.creditcard.settlement.service;

import com.creditcard.settlement.domain.MerchantSettlement;
import com.creditcard.settlement.domain.SettlementBatch;
import com.creditcard.settlement.exception.BatchAlreadyRunningException;
import com.creditcard.settlement.exception.MerchantSettlementNotFoundException;
import com.creditcard.settlement.repository.MerchantSettlementRepository;
import com.creditcard.settlement.repository.SettlementBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettlementServiceTest {

    @Mock
    private JobLauncher jobLauncher;

    @Mock
    private Job settlementJob;

    @Mock
    private SettlementBatchRepository settlementBatchRepository;

    @Mock
    private MerchantSettlementRepository merchantSettlementRepository;

    @InjectMocks
    private SettlementService settlementService;

    @BeforeEach
    void setUp() {
        when(settlementBatchRepository.findFirstByStatusOrderByStartedAtDesc("RUNNING"))
                .thenReturn(Optional.empty());
        when(settlementBatchRepository.save(any(SettlementBatch.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void launchJob_noRunningBatch_completedSuccessfully() throws Exception {
        JobExecution execution = mock(JobExecution.class);
        when(execution.getExitStatus()).thenReturn(ExitStatus.COMPLETED);
        when(jobLauncher.run(eq(settlementJob), any(JobParameters.class))).thenReturn(execution);

        SettlementBatch result = settlementService.launchJob("TEST");

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getTriggeredBy()).isEqualTo("TEST");
        assertThat(result.getBatchId()).isNotNull();
        assertThat(result.getCompletedAt()).isNotNull();
    }

    @Test
    void launchJob_batchAlreadyRunning_throwsBatchAlreadyRunningException() {
        SettlementBatch running = new SettlementBatch();
        running.setBatchId("already-running-id");
        when(settlementBatchRepository.findFirstByStatusOrderByStartedAtDesc("RUNNING"))
                .thenReturn(Optional.of(running));

        assertThatThrownBy(() -> settlementService.launchJob("TEST"))
                .isInstanceOf(BatchAlreadyRunningException.class);
    }

    @Test
    void launchJob_jobExitsFailed_batchStatusIsFailed() throws Exception {
        JobExecution execution = mock(JobExecution.class);
        when(execution.getExitStatus()).thenReturn(ExitStatus.FAILED);
        when(jobLauncher.run(eq(settlementJob), any(JobParameters.class))).thenReturn(execution);

        SettlementBatch result = settlementService.launchJob("TEST");

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getFailureReason()).contains("FAILED");
    }

    @Test
    void launchJob_jobLauncherThrows_statusIsFailedAndExceptionWrapped() throws Exception {
        when(jobLauncher.run(eq(settlementJob), any(JobParameters.class)))
                .thenThrow(new RuntimeException("launcher error"));

        assertThatThrownBy(() -> settlementService.launchJob("TEST"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Settlement job failed");

        ArgumentCaptor<SettlementBatch> captor = ArgumentCaptor.forClass(SettlementBatch.class);
        verify(settlementBatchRepository, atLeastOnce()).save(captor.capture());
        boolean anyFailed = captor.getAllValues().stream()
                .anyMatch(b -> "FAILED".equals(b.getStatus()));
        assertThat(anyFailed).isTrue();
    }

    @Test
    void launchJob_jobParamsContainTriggeredByAndBatchId() throws Exception {
        JobExecution execution = mock(JobExecution.class);
        when(execution.getExitStatus()).thenReturn(ExitStatus.COMPLETED);
        ArgumentCaptor<JobParameters> paramsCaptor = ArgumentCaptor.forClass(JobParameters.class);
        when(jobLauncher.run(eq(settlementJob), paramsCaptor.capture())).thenReturn(execution);

        settlementService.launchJob("SCHEDULER");

        JobParameters params = paramsCaptor.getValue();
        assertThat(params.getString("triggeredBy")).isEqualTo("SCHEDULER");
        assertThat(params.getString("batchId")).isNotNull();
        assertThat(params.getString("triggeredAt")).isNotNull();
    }

    @Test
    void findBatch_existingBatchId_returnsBatch() {
        SettlementBatch batch = new SettlementBatch();
        batch.setBatchId("batch-123");
        when(settlementBatchRepository.findById("batch-123")).thenReturn(Optional.of(batch));

        SettlementBatch result = settlementService.findBatch("batch-123");

        assertThat(result.getBatchId()).isEqualTo("batch-123");
    }

    @Test
    void findBatch_unknownBatchId_throwsNoSuchElementException() {
        when(settlementBatchRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementService.findBatch("unknown"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void findMerchantSettlement_found_returnsMerchantSettlement() {
        MerchantSettlement ms = new MerchantSettlement();
        ms.setMerchantId("merchant-A");
        ms.setBatchId("batch-001");
        when(merchantSettlementRepository.findByBatchIdAndMerchantId("batch-001", "merchant-A"))
                .thenReturn(Optional.of(ms));

        MerchantSettlement result = settlementService.findMerchantSettlement("merchant-A", "batch-001");

        assertThat(result.getMerchantId()).isEqualTo("merchant-A");
    }

    @Test
    void findMerchantSettlement_notFound_throwsMerchantSettlementNotFoundException() {
        when(merchantSettlementRepository.findByBatchIdAndMerchantId("batch-001", "ghost"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementService.findMerchantSettlement("ghost", "batch-001"))
                .isInstanceOf(MerchantSettlementNotFoundException.class);
    }
}
