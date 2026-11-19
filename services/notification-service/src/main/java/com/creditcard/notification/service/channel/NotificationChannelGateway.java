package com.creditcard.notification.service.channel;

public interface NotificationChannelGateway {

    NotificationChannel channel();

    /**
     * Sends one message. Returns {@link DeliveryOutcome#SENT} only when a message actually
     * left the service; {@link DeliveryOutcome#LOGGED} when the channel is log-only. Throws
     * when the send fails, so the caller can retry and record the failure.
     */
    DeliveryOutcome send(String recipient, String subject, String body);

    enum NotificationChannel {
        EMAIL, SMS
    }

    enum DeliveryOutcome {
        SENT, LOGGED
    }
}
