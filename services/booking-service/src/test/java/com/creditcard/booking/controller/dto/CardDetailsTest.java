package com.creditcard.booking.controller.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CardDetailsTest {

    @Test
    void toStringNeverContainsTheCardNumberCvvExpiryOrName() {
        String text = new CardDetails("5102589999999913", "10/50", "6173", "Jane Doe").toString();

        assertThat(text).doesNotContain("5102589999999913").doesNotContain("6173")
                .doesNotContain("10/50").doesNotContain("Jane");
    }
}
