package com.creditcard.notification.service.channel;

public interface NotificationChannelGateway {

    NotificationChannel channel();

    void send(String recipient, String subject, String body);

    enum NotificationChannel {
        EMAIL, SMS
    }
}
