package com.creditcard.notification.config.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PanMaskingFilterTest {

    private PanMaskingFilter filter;

    @BeforeEach
    void setUp() {
        filter = new PanMaskingFilter();
    }

    @Test
    void shouldDenyWhenFormatContainsPan() {
        FilterReply reply = filter.decide(null, null, Level.INFO, "Card: 4111111111111111", null, null);
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }

    @Test
    void shouldDenyWhenFormattedMessageContainsPan() {
        FilterReply reply = filter.decide(null, null, Level.INFO, "Card: {}", new Object[]{"4111111111111111"}, null);
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }

    @Test
    void shouldReturnNeutralForNonPanMessage() {
        FilterReply reply = filter.decide(null, null, Level.INFO, "Payment approved for card ending in 1234", null, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void shouldReturnNeutralWhenFormatIsNull() {
        FilterReply reply = filter.decide(null, null, Level.INFO, null, null, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void shouldReturnNeutralWhenFormatIsNullAndParamsPresent() {
        FilterReply reply = filter.decide(null, null, Level.INFO, null, new Object[]{"some param"}, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void shouldReturnNeutralForEmptyMessage() {
        FilterReply reply = filter.decide(null, null, Level.INFO, "", null, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void shouldDenyWhenMultiplePansInFormattedMessage() {
        FilterReply reply = filter.decide(null, null, Level.INFO, "{} and {}",
                new Object[]{"4111111111111111", "5500005555555559"}, null);
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }
}
