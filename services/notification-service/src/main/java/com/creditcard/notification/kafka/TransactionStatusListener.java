package com.creditcard.notification.kafka;

import com.creditcard.notification.kafka.event.TransactionStatusEvent;
import com.creditcard.notification.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class TransactionStatusListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionStatusListener.class);

    private final NotificationService notificationService;

    public TransactionStatusListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(
        topics = "transaction.status.updated",
        containerFactory = "txStatusContainerFactory",
        groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(ConsumerRecord<String, TransactionStatusEvent> record, Acknowledgment ack) {
        try {
            if (record.value() == null) {
                log.warn("Received null TransactionStatusEvent payload — discarding");
                return;
            }
            notificationService.processTransactionEvent(record.value());
        } catch (Exception e) {
            log.warn("Error processing TransactionStatusEvent: {}", e.getMessage());
        } finally {
            ack.acknowledge();
        }
    }
}
