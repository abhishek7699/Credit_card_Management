package com.creditcard.notification.service.channel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SmsGatewayTest {

    private final SmsGateway gateway = new SmsGateway();

    @Test
    void channelIsSms() {
        assertThat(gateway.channel()).isEqualTo(NotificationChannelGateway.NotificationChannel.SMS);
    }

    @Test
    void sendIsRecordOnlyAndReturnsLogged() {
        assertThat(gateway.send("***-***-0000", "ALERT", "body"))
                .isEqualTo(NotificationChannelGateway.DeliveryOutcome.LOGGED);
    }
}
