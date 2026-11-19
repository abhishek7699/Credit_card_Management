package com.creditcard.notification.service.channel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailGatewayTest {

    private final EmailGateway gateway = new EmailGateway();

    @Test
    void channelIsEmail() {
        assertThat(gateway.channel()).isEqualTo(NotificationChannelGateway.NotificationChannel.EMAIL);
    }

    @Test
    void logOnlySendReturnsLoggedNeverSent() {
        assertThat(gateway.send("j***@example.com", "Payment Approved", "Your payment was approved."))
                .isEqualTo(NotificationChannelGateway.DeliveryOutcome.LOGGED);
    }
}
