package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.TransactionStatus;
import com.creditcard.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionTimeoutSweepTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionService transactionService;

    private TransactionTimeoutSweep sweep(boolean enabled) {
        return new TransactionTimeoutSweep(transactionRepository, transactionService, 300, enabled);
    }

    @Test
    void sweepTimesOutEachStuckRowAndCountsOnlyTheWins() {
        UUID won = UUID.randomUUID();
        UUID lost = UUID.randomUUID();
        when(transactionRepository.findIdsByStatusCreatedBefore(eq(TransactionStatus.PENDING), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(won, lost));
        when(transactionService.timeOutPending(eq(won), any(Instant.class))).thenReturn(true);
        when(transactionService.timeOutPending(eq(lost), any(Instant.class))).thenReturn(false); // another replica won

        assertThat(sweep(true).sweepOnce()).isEqualTo(1);
    }

    @Test
    void aRowThatFailsForItsOwnReasonIsLoggedAndTheSweepContinues() {
        UUID broken = UUID.randomUUID();
        UUID fine = UUID.randomUUID();
        when(transactionRepository.findIdsByStatusCreatedBefore(any(), any(), any())).thenReturn(List.of(broken, fine));
        when(transactionService.timeOutPending(eq(broken), any(Instant.class)))
                .thenThrow(new IllegalStateException("row is locked by a bad state"));
        when(transactionService.timeOutPending(eq(fine), any(Instant.class))).thenReturn(true);

        assertThat(sweep(true).sweepOnce()).isEqualTo(1);
        verify(transactionService).timeOutPending(eq(fine), any(Instant.class));
    }

    @Test
    void selectsOnlyPendingRowsOlderThanTheTimeoutInOneBatch() {
        when(transactionRepository.findIdsByStatusCreatedBefore(any(), any(), any())).thenReturn(List.of());
        Instant before = Instant.now();

        assertThat(sweep(true).sweepOnce()).isZero();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findIdsByStatusCreatedBefore(eq(TransactionStatus.PENDING), cutoff.capture(), page.capture());
        Instant after = Instant.now();
        assertThat(cutoff.getValue()).isBetween(before.minus(Duration.ofSeconds(300)), after.minus(Duration.ofSeconds(300)));
        assertThat(page.getValue().getPageSize()).isEqualTo(TransactionTimeoutSweep.BATCH_SIZE);
        verify(transactionService, never()).timeOutPending(any(), any());
    }

    @Test
    void scheduledPassRunsTheSweepOnlyWhenEnabled() {
        when(transactionRepository.findIdsByStatusCreatedBefore(any(), any(), any())).thenReturn(List.of());

        sweep(true).sweepScheduled();
        verify(transactionRepository).findIdsByStatusCreatedBefore(any(), any(), any());

        sweep(false).sweepScheduled();
        verifyNoMoreInteractions(transactionRepository);
    }
}
