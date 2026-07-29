package com.creditcard.notification.service.channel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class SmsGatewayTest {

    private final SmsGateway gateway = new SmsGateway();

    @Test
    void channelIsSms() {
        assertThat(gateway.channel()).isEqualTo(NotificationChannelGateway.NotificationChannel.SMS);
    }

    @Test
    void sendDoesNotThrow() {
        assertThatNoException().isThrownBy(() ->
                gateway.send("***-***-5678", null, "ALERT: Suspicious activity on your card."));
    }
}
