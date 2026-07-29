package com.creditcard.gateway.config.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class PanMaskingFilterTest {

    private PanMaskingFilter filter;
    private ILoggingEvent event;

    @BeforeEach
    void setUp() {
        filter = new PanMaskingFilter();
        event = Mockito.mock(ILoggingEvent.class);
    }

    @Test
    void pan16DigitsInFormatString_blocked() {
        when(event.getMessage()).thenReturn("Card: 4111111111111111");
        when(event.getArgumentArray()).thenReturn(null);
        assertThat(filter.decide(null, null, null, "Card: 4111111111111111", null, null))
                .isEqualTo(FilterReply.DENY);
    }

    @Test
    void pan13DigitsInFormatString_blocked() {
        when(event.getMessage()).thenReturn("Card: 4111111111111");
        when(event.getArgumentArray()).thenReturn(null);
        assertThat(filter.decide(null, null, null, "Card: 4111111111111", null, null))
                .isEqualTo(FilterReply.DENY);
    }

    @Test
    void pan19DigitsInFormatString_blocked() {
        when(event.getMessage()).thenReturn("Card: 4111111111111111111");
        assertThat(filter.decide(null, null, null, "Card: 4111111111111111111", null, null))
                .isEqualTo(FilterReply.DENY);
    }

    @Test
    void number12Digits_notBlocked() {
        // 12 digits — below PAN minimum
        assertThat(filter.decide(null, null, null, "Ref: 123456789012", null, null))
                .isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void nullFormat_returnsNeutral() {
        assertThat(filter.decide(null, null, null, null, null, null))
                .isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void panInFirstParam_blocked() {
        assertThat(filter.decide(null, null, null, "Card number: {}",
                new Object[]{"4111111111111111"}, null))
                .isEqualTo(FilterReply.DENY);
    }

    @Test
    void panInSecondParam_blocked() {
        assertThat(filter.decide(null, null, null, "Merchant {} card {}",
                new Object[]{"merchant-001", "4111111111111111"}, null))
                .isEqualTo(FilterReply.DENY);
    }

    @Test
    void maskedCardNumber_notBlocked() {
        // ****-****-****-1111 does not match \b\d{13,19}\b
        assertThat(filter.decide(null, null, null, "Masked: ****-****-****-1111", null, null))
                .isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void nullParams_safeFormat_returnsNeutral() {
        assertThat(filter.decide(null, null, null, "User logged in: merchant-001", null, null))
                .isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void emptyParams_safeFormat_returnsNeutral() {
        assertThat(filter.decide(null, null, null, "Ping OK", new Object[]{}, null))
                .isEqualTo(FilterReply.NEUTRAL);
    }
}
