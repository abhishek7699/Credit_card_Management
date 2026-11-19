package com.creditcard.notification.service.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** SMS is record-only for now: nothing is sent, so the outcome is LOGGED. */
@Component
public class SmsGateway implements NotificationChannelGateway {

    private static final Logger log = LoggerFactory.getLogger(SmsGateway.class);

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public DeliveryOutcome send(String recipient, String subject, String body) {
        log.info("SMS logged, not sent");
        return DeliveryOutcome.LOGGED;
    }
}
