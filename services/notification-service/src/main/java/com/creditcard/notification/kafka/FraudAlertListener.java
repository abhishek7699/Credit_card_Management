package com.creditcard.notification.kafka;

import com.creditcard.notification.kafka.event.FraudAlertEvent;
import com.creditcard.notification.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class FraudAlertListener {

    private static final Logger log = LoggerFactory.getLogger(FraudAlertListener.class);

    private final NotificationService notificationService;

    public FraudAlertListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(
        topics = "fraud.alert.raised",
        containerFactory = "fraudAlertContainerFactory",
        groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(ConsumerRecord<String, FraudAlertEvent> record, Acknowledgment ack) {
        try {
            if (record.value() == null) {
                log.warn("Received null FraudAlertEvent payload — discarding");
                return;
            }
            notificationService.processFraudAlertEvent(record.value());
        } catch (Exception e) {
            log.warn("Error processing FraudAlertEvent: {}", e.getMessage());
        } finally {
            ack.acknowledge();
        }
    }
}
