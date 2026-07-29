package com.creditcard.notification.service;

import com.creditcard.notification.domain.CardholderContact;
import com.creditcard.notification.domain.NotificationRecord;
import com.creditcard.notification.kafka.event.FraudAlertEvent;
import com.creditcard.notification.kafka.event.TransactionStatusEvent;
import com.creditcard.notification.repository.CardholderContactRepository;
import com.creditcard.notification.repository.NotificationRecordRepository;
import com.creditcard.notification.repository.ProcessedEventRepository;
import com.creditcard.notification.service.channel.NotificationChannelGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceBranchTest {

    @Mock private CardholderContactRepository contactRepo;
    @Mock private NotificationRecordRepository recordRepo;
    @Mock private ProcessedEventRepository processedEventRepo;
    @Mock private NotificationChannelGateway emailGateway;
    @Mock private NotificationChannelGateway smsGateway;

    private CardholderContact contactWithBoth() {
        CardholderContact c = new CardholderContact();
        c.setCardToken("vault:v1:tok");
        c.setMaskedCardNumber("****-****-****-1234");
        c.setEmail("user@example.com");
        c.setPhoneNumber("+14155551234");
        c.setTransactionNotificationsEnabled(true);
        return c;
    }

    private NotificationService service() {
        when(emailGateway.channel()).thenReturn(NotificationChannelGateway.NotificationChannel.EMAIL);
        when(smsGateway.channel()).thenReturn(NotificationChannelGateway.NotificationChannel.SMS);
        return new NotificationService(contactRepo, recordRepo, processedEventRepo,
                List.of(emailGateway, smsGateway));
    }

    // ── Null guard branches in processTransactionEvent ──────────────────────────

    @Test
    void txEvent_nullEventId_discarded() {
        NotificationService svc = service();
        TransactionStatusEvent event = new TransactionStatusEvent();
        event.setEventId(null);
        event.setTransactionId("tx-001");

        svc.processTransactionEvent(event);

        verify(processedEventRepo, never()).insertIfAbsent(anyString());
    }

    @Test
    void txEvent_nullTransactionId_discarded() {
        NotificationService svc = service();
        TransactionStatusEvent event = new TransactionStatusEvent();
        event.setEventId("e-001");
        event.setTransactionId(null);

        svc.processTransactionEvent(event);

        verify(processedEventRepo, never()).insertIfAbsent(anyString());
    }

    // ── Null guard branches in processFraudAlertEvent ───────────────────────────

    @Test
    void fraudEvent_nullEventId_discarded() {
        NotificationService svc = service();
        FraudAlertEvent event = new FraudAlertEvent();
        event.setEventId(null);
        event.setTransactionId("tx-001");

        svc.processFraudAlertEvent(event);

        verify(processedEventRepo, never()).insertIfAbsent(anyString());
    }

    @Test
    void fraudEvent_nullTransactionId_discarded() {
        NotificationService svc = service();
        FraudAlertEvent event = new FraudAlertEvent();
        event.setEventId("e-001");
        event.setTransactionId(null);

        svc.processFraudAlertEvent(event);

        verify(processedEventRepo, never()).insertIfAbsent(anyString());
    }

    // ── No contact registered for fraud event ───────────────────────────────────

    @Test
    void fraudEvent_noContact_savesKeyAndReturns() {
        NotificationService svc = service();
        when(processedEventRepo.insertIfAbsent(anyString())).thenReturn(1);
        when(contactRepo.findById(anyString())).thenReturn(Optional.empty());

        FraudAlertEvent event = new FraudAlertEvent();
        event.setEventId("e-002");
        event.setTransactionId("tx-002");
        event.setCardToken("vault:v1:no_contact");

        svc.processFraudAlertEvent(event);

        verify(recordRepo, never()).saveAll(anyList());
    }

    // ── TransactionFailed content ────────────────────────────────────────────────

    @Test
    void buildTransactionSubjectFailed() {
        assertThat(NotificationService.buildTransactionSubject("TransactionFailed"))
                .isEqualTo("Payment Failed");
    }

    @Test
    void buildTransactionBodyFailed() {
        String body = NotificationService.buildTransactionBody(
                "TransactionFailed", "5678", "tx-003", "CARD_NOT_ACTIVE");
        assertThat(body).contains("failed").contains("CARD_NOT_ACTIVE").contains("5678");
    }

    // ── dispatchSingle — FAILED status when gateway throws ──────────────────────

    @Test
    void dispatchSingle_gatewaySendThrows_recordUpdatedToFailed() {
        NotificationService svc = service();
        when(processedEventRepo.insertIfAbsent(anyString())).thenReturn(1);
        when(contactRepo.findById(anyString())).thenReturn(Optional.of(contactWithBoth()));
        when(recordRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(recordRepo.save(any(NotificationRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("SMTP down")).when(emailGateway).send(anyString(), anyString(), anyString());

        TransactionStatusEvent event = new TransactionStatusEvent();
        event.setEventId("e-throw");
        event.setEventType("TransactionAuthorized");
        event.setTransactionId("tx-throw");
        event.setCardToken("vault:v1:tok");

        svc.processTransactionEvent(event);

        ArgumentCaptor<NotificationRecord> captor = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(recordRepo, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).anyMatch(r -> "FAILED".equals(r.getStatus()));
    }

    // ── buildPendingRecords — null email recipient skipped ───────────────────────

    @Test
    void txEvent_emailNullContact_onlySmsRecordCreated() {
        NotificationService svc = service();
        CardholderContact emailNull = new CardholderContact();
        emailNull.setCardToken("vault:v1:tok");
        emailNull.setMaskedCardNumber("****-****-****-5678");
        emailNull.setEmail(null);
        emailNull.setPhoneNumber("+14155551234");
        emailNull.setTransactionNotificationsEnabled(true);

        when(processedEventRepo.insertIfAbsent(anyString())).thenReturn(1);
        when(contactRepo.findById(anyString())).thenReturn(Optional.of(emailNull));
        when(recordRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(recordRepo.save(any(NotificationRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionStatusEvent event = new TransactionStatusEvent();
        event.setEventId("e-sms-only");
        event.setEventType("TransactionAuthorized");
        event.setTransactionId("tx-sms-only");
        event.setCardToken("vault:v1:tok");

        svc.processTransactionEvent(event);

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(recordRepo).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        NotificationRecord record = (NotificationRecord) captor.getValue().get(0);
        assertThat(record.getChannel()).isEqualTo("SMS");
    }

    // ── updateRecordStatus — SENT sets sentAt ────────────────────────────────────

    @Test
    void updateRecordStatus_sentSetsSentAt() {
        NotificationService svc = service();
        when(processedEventRepo.insertIfAbsent(anyString())).thenReturn(1);
        when(contactRepo.findById(anyString())).thenReturn(Optional.of(contactWithBoth()));
        when(recordRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(recordRepo.save(any(NotificationRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionStatusEvent event = new TransactionStatusEvent();
        event.setEventId("e-sent");
        event.setEventType("TransactionAuthorized");
        event.setTransactionId("tx-sent");
        event.setCardToken("vault:v1:tok");

        svc.processTransactionEvent(event);

        ArgumentCaptor<NotificationRecord> captor = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(recordRepo, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).anyMatch(r -> "SENT".equals(r.getStatus()) && r.getSentAt() != null);
    }
}
