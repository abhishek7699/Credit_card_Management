package com.creditcard.reporting.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void titleAndDescriptionIdentifyTheService() {
        var info = new OpenApiConfig().openApi().getInfo();

        assertThat(info.getTitle()).isEqualTo("Reporting Service API");
        assertThat(info.getDescription()).isEqualTo("Read-only transaction, merchant, and settlement reporting.");
    }
}
