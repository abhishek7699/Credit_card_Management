package com.creditcard.notification.service.channel;

import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

class EmailChannelTest {

    @Test
    void sendBuildsPlainTextMessageAndReturnsSent() {
        JavaMailSender sender = mock(JavaMailSender.class);
        EmailChannel channel = new EmailChannel(sender, "from@creditcard.local");

        var outcome = channel.send("user@example.com", "Payment Approved", "Card ending in 5678.");

        var captor = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly("user@example.com");
        assertThat(sent.getFrom()).isEqualTo("from@creditcard.local");
        assertThat(sent.getSubject()).isEqualTo("Payment Approved");
        assertThat(sent.getText()).isEqualTo("Card ending in 5678.");
        assertThat(outcome).isEqualTo(NotificationChannelGateway.DeliveryOutcome.SENT);
        assertThat(channel.channel()).isEqualTo(NotificationChannelGateway.NotificationChannel.EMAIL);
    }

    @Test
    void mailFailurePropagatesSoCallerCanRetry() {
        JavaMailSender sender = mock(JavaMailSender.class);
        doThrow(new MailSendException("smtp down")).when(sender).send(any(SimpleMailMessage.class));
        EmailChannel channel = new EmailChannel(sender, "from@creditcard.local");

        assertThatThrownBy(() -> channel.send("user@example.com", "s", "b"))
                .isInstanceOf(MailSendException.class);
    }
}
