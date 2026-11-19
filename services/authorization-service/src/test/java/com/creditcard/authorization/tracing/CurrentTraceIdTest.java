package com.creditcard.authorization.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurrentTraceIdTest {

    @Test
    void returnsTheActiveSpansTraceId() {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("4bf92f3577b34da6a3ce929d0e0e4736");

        assertThat(new CurrentTraceId(tracer).get()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void returnsNullWhenNoSpanIsCurrent() {
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(null);

        assertThat(new CurrentTraceId(tracer).get()).isNull();
    }
}
