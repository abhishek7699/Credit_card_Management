package com.creditcard.frauddetection.service;

import com.creditcard.frauddetection.domain.FraudAlert;
import com.creditcard.frauddetection.domain.TransactionRecord;
import com.creditcard.frauddetection.kafka.event.FraudAlertRaisedEvent;
import com.creditcard.frauddetection.kafka.event.TransactionInitiatedEvent;
import com.creditcard.frauddetection.repository.FraudAlertRepository;
import com.creditcard.frauddetection.repository.TransactionRecordRepository;
import com.creditcard.frauddetection.rules.FraudRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudDetectionServiceTest {

    @Mock
    private FraudRule velocityRule;

    @Mock
    private FraudRule anomalyRule;

    @Mock
    private FraudAlertRepository fraudAlertRepository;

    @Mock
    private TransactionRecordRepository transactionRecordRepository;

    private FraudDetectionService fraudDetectionService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        fraudDetectionService = new FraudDetectionService(
                List.of(velocityRule, anomalyRule),
                fraudAlertRepository,
                transactionRecordRepository
        );
    }

    @Test
    void shouldReturnEmptyWhenNoRulesFire() {
        when(velocityRule.evaluate(any())).thenReturn(Optional.empty());
        when(anomalyRule.evaluate(any())).thenReturn(Optional.empty());
        when(transactionRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Optional<FraudAlertRaisedEvent> result = fraudDetectionService.evaluate(buildEvent());

        assertThat(result).isEmpty();
        verify(fraudAlertRepository, never()).save(any());
    }

    @Test
    void shouldReturnAlertEventWhenOneRuleFires() {
        when(velocityRule.evaluate(any())).thenReturn(Optional.of("VELOCITY_EXCEEDED"));
        when(anomalyRule.evaluate(any())).thenReturn(Optional.empty());
        when(transactionRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(fraudAlertRepository.save(any())).thenAnswer(i -> {
            FraudAlert alert = i.getArgument(0);
            alert.setId(UUID.randomUUID());
            return alert;
        });

        Optional<FraudAlertRaisedEvent> result = fraudDetectionService.evaluate(buildEvent());

        assertThat(result).isPresent();
        assertThat(result.get().getEventType()).isEqualTo("FraudAlertRaised");
        assertThat(result.get().getTriggeredRuleTypes()).containsExactly("VELOCITY_EXCEEDED");
    }

    @Test
    void shouldReturnSingleAlertWithAllRuleTypesWhenMultipleRulesFire() {
        when(velocityRule.evaluate(any())).thenReturn(Optional.of("VELOCITY_EXCEEDED"));
        when(anomalyRule.evaluate(any())).thenReturn(Optional.of("AMOUNT_ANOMALY"));
        when(transactionRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(fraudAlertRepository.save(any())).thenAnswer(i -> {
            FraudAlert alert = i.getArgument(0);
            alert.setId(UUID.randomUUID());
            return alert;
        });

        Optional<FraudAlertRaisedEvent> result = fraudDetectionService.evaluate(buildEvent());

        assertThat(result).isPresent();
        assertThat(result.get().getTriggeredRuleTypes())
                .containsExactlyInAnyOrder("VELOCITY_EXCEEDED", "AMOUNT_ANOMALY");

        ArgumentCaptor<FraudAlert> alertCaptor = ArgumentCaptor.forClass(FraudAlert.class);
        verify(fraudAlertRepository).save(alertCaptor.capture());
        assertThat(alertCaptor.getValue().getTriggeredRules())
                .containsExactlyInAnyOrder("VELOCITY_EXCEEDED", "AMOUNT_ANOMALY");
    }

    @Test
    void shouldAlwaysSaveTransactionRecord() {
        when(velocityRule.evaluate(any())).thenReturn(Optional.empty());
        when(anomalyRule.evaluate(any())).thenReturn(Optional.empty());
        when(transactionRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        fraudDetectionService.evaluate(buildEvent());

        ArgumentCaptor<TransactionRecord> recordCaptor = ArgumentCaptor.forClass(TransactionRecord.class);
        verify(transactionRecordRepository).save(recordCaptor.capture());
        assertThat(recordCaptor.getValue().getCardToken()).isEqualTo("vault:v1:tok");
    }

    @Test
    void shouldTreatRuleExceptionAsFailSafe() {
        when(velocityRule.evaluate(any())).thenThrow(new RuntimeException("DB error"));
        when(anomalyRule.evaluate(any())).thenReturn(Optional.empty());
        when(transactionRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Optional<FraudAlertRaisedEvent> result = fraudDetectionService.evaluate(buildEvent());

        assertThat(result).isEmpty();
        verify(fraudAlertRepository, never()).save(any());
    }

    private TransactionInitiatedEvent buildEvent() {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("TransactionInitiated");
        event.setSourceService("transaction-service");
        event.setTraceId("trace-001");
        event.setOccurredAt(Instant.now());

        TransactionInitiatedEvent.Payload payload = new TransactionInitiatedEvent.Payload();
        payload.setTransactionId(UUID.randomUUID());
        payload.setCardToken("vault:v1:tok");
        payload.setAmount(new BigDecimal("100.00"));
        payload.setCurrencyCode("USD");
        event.setPayload(payload);
        return event;
    }
}
