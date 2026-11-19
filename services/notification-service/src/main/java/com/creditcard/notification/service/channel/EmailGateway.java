package com.creditcard.notification.service.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Log-only email fallback, used when notification.channels.email.enabled is false (the
 * default). Nothing is sent, so the outcome is LOGGED, never SENT.
 */
@Component
@ConditionalOnProperty(name = "notification.channels.email.enabled", havingValue = "false", matchIfMissing = true)
public class EmailGateway implements NotificationChannelGateway {

    private static final Logger log = LoggerFactory.getLogger(EmailGateway.class);

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public DeliveryOutcome send(String recipient, String subject, String body) {
        log.info("EMAIL logged, not sent (email channel disabled) — subject: {}", subject);
        return DeliveryOutcome.LOGGED;
    }
}
