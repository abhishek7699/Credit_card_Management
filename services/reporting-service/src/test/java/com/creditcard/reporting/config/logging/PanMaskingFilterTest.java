package com.creditcard.reporting.config.logging;

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
        logger = (Logger) LoggerFactory.getLogger(PanMaskingFilterTest.class);
    }

    @Test
    void panInMessage_returnsDeny() {
        FilterReply reply = filter.decide(null, logger, Level.INFO, "Card 4111111111111111 processed", null, null);
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }

    @Test
    void nonPanMessage_returnsNeutral() {
        FilterReply reply = filter.decide(null, logger, Level.INFO, "Processing transaction tx-001", null, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void nullFormat_returnsNeutral() {
        FilterReply reply = filter.decide(null, logger, Level.INFO, null, null, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void nullFormatWithParams_returnsNeutral() {
        FilterReply reply = filter.decide(null, logger, Level.INFO, null, new Object[]{"param"}, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void emptyMessage_returnsNeutral() {
        FilterReply reply = filter.decide(null, logger, Level.INFO, "", null, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void multiplePansInMessage_returnsDeny() {
        FilterReply reply = filter.decide(null, logger, Level.INFO,
                "Cards 4111111111111111 and 5500000000000004", null, null);
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }

    @Test
    void thirteenDigitPan_returnsDeny() {
        FilterReply reply = filter.decide(null, logger, Level.INFO, "PAN: 4000001234567", null, null);
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }

    @Test
    void nineteenDigitPan_returnsDeny() {
        FilterReply reply = filter.decide(null, logger, Level.INFO, "PAN: 6011000990139424001", null, null);
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }

    @Test
    void panInParams_returnsDeny() {
        FilterReply reply = filter.decide(null, logger, Level.INFO,
                "Card {} processed", new Object[]{"4111111111111111"}, null);
        assertThat(reply).isEqualTo(FilterReply.DENY);
    }

    @Test
    void noPanInFormatOrParams_returnsNeutral() {
        FilterReply reply = filter.decide(null, logger, Level.INFO,
                "Processing tx {}", new Object[]{"tx-abc-123"}, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void emptyParams_returnsNeutral() {
        FilterReply reply = filter.decide(null, logger, Level.INFO,
                "No PAN here", new Object[]{}, null);
        assertThat(reply).isEqualTo(FilterReply.NEUTRAL);
    }
}
