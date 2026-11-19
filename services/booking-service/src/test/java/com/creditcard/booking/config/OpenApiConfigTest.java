package com.creditcard.booking.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void titleAndDescriptionIdentifyTheService() {
        var info = new OpenApiConfig().openApi().getInfo();

        assertThat(info.getTitle()).isEqualTo("Booking Service API");
        assertThat(info.getDescription()).isEqualTo("Hotel bookings, the room board, and checkout for front-desk staff.");
    }
}
