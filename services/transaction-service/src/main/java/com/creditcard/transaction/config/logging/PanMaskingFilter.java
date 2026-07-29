package com.creditcard.transaction.config.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

import java.util.regex.Pattern;

/**
 * Blocks any log event whose formatted message contains a sequence of 13–19 consecutive digits
 * (PAN pattern). This is a safety-net guard — no raw card data should reach the log pipeline.
 */
public class PanMaskingFilter extends TurboFilter {

    private static final Pattern PAN_PATTERN = Pattern.compile("\\b\\d{13,19}\\b");

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (format != null && PAN_PATTERN.matcher(format).find()) {
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }
}
