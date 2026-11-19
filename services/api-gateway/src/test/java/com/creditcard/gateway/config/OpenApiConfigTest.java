package com.creditcard.gateway.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void titleAndDescriptionIdentifyTheService() {
        var info = new OpenApiConfig().openApi().getInfo();

        assertThat(info.getTitle()).isEqualTo("API Gateway");
        assertThat(info.getDescription()).isEqualTo("Single entry point: routing, JWT authentication, and rate limiting for the platform APIs.");
    }
}
