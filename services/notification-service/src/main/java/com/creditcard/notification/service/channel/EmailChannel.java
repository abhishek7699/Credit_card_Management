package com.creditcard.notification.service.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Sends plain-text email over SMTP. Active only when notification.channels.email.enabled=true. */
@Component
@ConditionalOnProperty(name = "notification.channels.email.enabled", havingValue = "true")
public class EmailChannel implements NotificationChannelGateway {

    private final JavaMailSender mailSender;
    private final String from;

    public EmailChannel(JavaMailSender mailSender,
                        @Value("${notification.channels.email.from:notifications@creditcard.local}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public DeliveryOutcome send(String recipient, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
        return DeliveryOutcome.SENT;
    }
}
