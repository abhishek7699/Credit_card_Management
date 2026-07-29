package com.creditcard.settlement.config.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;
import org.slf4j.helpers.MessageFormatter;

import java.util.regex.Pattern;

public class PanMaskingFilter extends TurboFilter {

    private static final Pattern PAN_PATTERN = Pattern.compile("\\b\\d{13,19}\\b");

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (format == null) {
            return FilterReply.NEUTRAL;
        }
        if (PAN_PATTERN.matcher(format).find()) {
            return FilterReply.DENY;
        }
        if (params != null && params.length > 0) {
            String formatted = MessageFormatter.arrayFormat(format, params).getMessage();
            if (PAN_PATTERN.matcher(formatted).find()) {
                return FilterReply.DENY;
            }
        }
        return FilterReply.NEUTRAL;
    }
}
