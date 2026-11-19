package com.creditcard.notification.service;

import com.creditcard.notification.domain.CardholderContact;
import com.creditcard.notification.domain.NotificationRecord;
import com.creditcard.notification.kafka.event.TransactionStatusEvent;
import com.creditcard.notification.repository.CardholderContactRepository;
import com.creditcard.notification.repository.NotificationRecordRepository;
import com.creditcard.notification.repository.ProcessedEventRepository;
import com.creditcard.notification.service.channel.EmailChannel;
import com.creditcard.notification.service.channel.NotificationChannelGateway;
import com.creditcard.notification.service.channel.SmsGateway;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** One event, one email through the real EmailChannel against a mocked SMTP sender. */
class EmailDeliveryTest {

    private final CardholderContactRepository contactRepo = mock(CardholderContactRepository.class);
    private final NotificationRecordRepository recordRepo = mock(NotificationRecordRepository.class);
    private final ProcessedEventRepository processedRepo = mock(ProcessedEventRepository.class);
    private final JavaMailSender sender = mock(JavaMailSender.class);

    private NotificationService service() {
        NotificationService svc = new NotificationService(contactRepo, recordRepo, processedRepo,
                List.of(new EmailChannel(sender, "from@creditcard.local"), new SmsGateway()));
        svc.setDeliveryPolicy(3, 0L);
        when(recordRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(recordRepo.save(any(NotificationRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        CardholderContact c = new CardholderContact();
        c.setCardToken("vault:v1:tok");
        c.setMaskedCardNumber("****-****-****-5678");
        c.setEmail("user@example.com");
        c.setPhoneNumber("+14155551234");
        c.setTransactionNotificationsEnabled(true);
        when(contactRepo.findById(anyString())).thenReturn(Optional.of(c));
        return svc;
    }

    private TransactionStatusEvent event() {
        TransactionStatusEvent e = new TransactionStatusEvent();
        e.setEventId("evt-1");
        e.setEventType("TransactionAuthorized");
        TransactionStatusEvent.Payload p = new TransactionStatusEvent.Payload();
        p.setTransactionId("tx-1");
        p.setCardToken("vault:v1:tok");
        e.setPayload(p);
        return e;
    }

    @Test
    void oneEventSendsOneEmailWithMaskedCardOnly() {
        NotificationService svc = service();
        when(processedRepo.insertIfAbsent(anyString())).thenReturn(1);

        svc.processTransactionEvent(event());

        var captor = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, times(1)).send(captor.capture());
        assertThat(captor.getValue().getText()).contains("5678").doesNotContain("****-****-****-5678");
        assertThat(captor.getValue().getTo()).containsExactly("user@example.com");
    }

    @Test
    void duplicateRedeliverySendsNoSecondEmail() {
        NotificationService svc = service();
        when(processedRepo.insertIfAbsent(anyString())).thenReturn(1).thenReturn(0);

        svc.processTransactionEvent(event());
        svc.processTransactionEvent(event());

        verify(sender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void sendFailureIsRetriedThreeTimesAndNeverMarkedSent() {
        NotificationService svc = service();
        when(processedRepo.insertIfAbsent(anyString())).thenReturn(1);
        doThrow(new RuntimeException("smtp down")).when(sender).send(any(SimpleMailMessage.class));

        svc.processTransactionEvent(event());

        verify(sender, times(3)).send(any(SimpleMailMessage.class));
        var statuses = org.mockito.ArgumentCaptor.forClass(NotificationRecord.class);
        verify(recordRepo, atLeastOnce()).save(statuses.capture());
        assertThat(statuses.getAllValues()).noneMatch(r -> "SENT".equals(r.getStatus()));
        assertThat(statuses.getAllValues()).anyMatch(r -> "FAILED".equals(r.getStatus()));
    }
}
