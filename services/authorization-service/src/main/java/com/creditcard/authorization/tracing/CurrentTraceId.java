package com.creditcard.authorization.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

/** Reads the traceId of the span that is current on this thread, for the Kafka envelope. */
@Component
public class CurrentTraceId {

    private final Tracer tracer;

    public CurrentTraceId(Tracer tracer) {
        this.tracer = tracer;
    }

    /** The active trace's id, or null when no span is current. */
    public String get() {
        Span span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }
}
