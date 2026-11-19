package com.creditcard.notification.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void titleAndDescriptionIdentifyTheService() {
        var info = new OpenApiConfig().openApi().getInfo();

        assertThat(info.getTitle()).isEqualTo("Notification Service API");
        assertThat(info.getDescription()).isEqualTo("Cardholder contact records and notification delivery.");
    }
}
