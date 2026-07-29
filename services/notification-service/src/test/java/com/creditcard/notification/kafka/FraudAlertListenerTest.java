package com.creditcard.notification.kafka;

import com.creditcard.notification.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FraudAlertListenerTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private FraudAlertListener listener;

    @Test
    void nullPayload_isDiscardedAndAcknowledged() {
        ConsumerRecord<String, ?> record = new ConsumerRecord<>("fraud.alert.raised", 0, 0L, "key", null);
        Acknowledgment ack = mock(Acknowledgment.class);

        listener.onMessage((ConsumerRecord) record, ack);

        verify(notificationService, never()).processFraudAlertEvent(any());
        verify(ack).acknowledge();
    }

    @Test
    void serviceThrows_offsetStillAcknowledged() {
        var event = new com.creditcard.notification.kafka.event.FraudAlertEvent();
        event.setEventId("e1");
        ConsumerRecord<String, com.creditcard.notification.kafka.event.FraudAlertEvent> record =
                new ConsumerRecord<>("fraud.alert.raised", 0, 0L, "key", event);
        Acknowledgment ack = mock(Acknowledgment.class);

        doThrow(new RuntimeException("Service error")).when(notificationService).processFraudAlertEvent(any());

        listener.onMessage(record, ack);

        verify(ack).acknowledge();
    }
}
