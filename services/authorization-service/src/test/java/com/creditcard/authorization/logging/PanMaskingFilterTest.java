package com.creditcard.authorization.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class PanMaskingFilterTest {

    private PanMaskingFilter filter;
    private Logger logger;

    @BeforeEach
    void setUp() {
        filter = new PanMaskingFilter();
        filter.start();
        logger = (Logger) LoggerFactory.getLogger(PanMaskingFilterTest.class);
    }

    @Test
    void shouldDenyEventContaining16DigitSequence() {
        FilterReply reply = filter.decide(null, logger, Level.INFO,
                "Processing card 4111111111111111 for transaction", null, null);
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }

    @Test
    void shouldDenyEventContaining13DigitSequence() {
        FilterReply reply = filter.decide(null, logger, Level.INFO,
                "Card: 4111111111111", null, null);
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }

    @Test
    void shouldDenyEventContaining19DigitSequence() {
        FilterReply reply = filter.decide(null, logger, Level.INFO,
                "Token raw: 4111111111111111111", null, null);
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }

    @Test
    void shouldNeutralEventWithNoDigitSequence() {
        FilterReply reply = filter.decide(null, logger, Level.INFO,
                "Authorization decision: APPROVED for vault:v1:token-abc", null, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void shouldNeutralEventWithShortDigitSequence() {
        FilterReply reply = filter.decide(null, logger, Level.INFO,
                "Amount: 100.00, txId: 123456789012", null, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void shouldDenyEventWithFormattedCardNumber() {
        FilterReply reply = filter.decide(null, logger, Level.INFO,
                "Card 4111-1111-1111-1111 processed", null, null);
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }

    @Test
    void shouldDenyWhenPanInParams() {
        FilterReply reply = filter.decide(null, logger, Level.INFO,
                "Card token: {}", new Object[]{"4111111111111111"}, null);
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }

    @Test
    void shouldNeutralWhenNullFormat() {
        FilterReply reply = filter.decide(null, logger, Level.INFO,
                null, null, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void shouldNeutralWhenNullFormatWithNonEmptyParams() {
        FilterReply reply = filter.decide(null, logger, Level.INFO,
                null, new Object[]{"some-value"}, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }
}
