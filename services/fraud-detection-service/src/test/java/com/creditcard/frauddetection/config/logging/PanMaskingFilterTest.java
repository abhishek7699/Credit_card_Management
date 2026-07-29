package com.creditcard.frauddetection.config.logging;

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
        FilterReply reply = filter.decide(null, null, Level.INFO, "card 4111 1111 1111 1111 used", null, null);
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }

    @Test
    void shouldReturnNeutralWhenFormatIsNull() {
        FilterReply reply = filter.decide(null, null, Level.INFO, null, null, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void shouldReturnNeutralWhenFormatHasNoPanAndNoParams() {
        FilterReply reply = filter.decide(null, null, Level.INFO, "clean message", null, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void shouldReturnNeutralWhenParamsArrayIsEmpty() {
        FilterReply reply = filter.decide(null, null, Level.INFO, "clean message", new Object[]{}, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void shouldDenyWhenFormattedMessageContainsPan() {
        FilterReply reply = filter.decide(null, null, Level.INFO, "card: {}", new Object[]{"4111111111111111"}, null);
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }

    @Test
    void shouldReturnNeutralWhenFormattedMessageHasNoPan() {
        FilterReply reply = filter.decide(null, null, Level.INFO, "user: {}", new Object[]{"john"}, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void shouldReturnNeutralWhenFormatIsNullAndParamsPresent() {
        // format=null → MessageFormatter returns null message → formatted==null branch
        FilterReply reply = filter.decide(null, null, Level.INFO, null, new Object[]{"some param"}, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }
}
