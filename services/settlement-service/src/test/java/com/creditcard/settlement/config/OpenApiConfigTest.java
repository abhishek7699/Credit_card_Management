package com.creditcard.settlement.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void titleAndDescriptionIdentifyTheService() {
        var info = new OpenApiConfig().openApi().getInfo();

        assertThat(info.getTitle()).isEqualTo("Settlement Service API");
        assertThat(info.getDescription()).isEqualTo("Settlement batch triggers, batch status, and merchant reports.");
    }
}
