package com.creditcard.notification.tracing;

import brave.context.slf4j.MDCScopeDecorator;
import brave.propagation.CurrentTraceContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MdcTracingConfigTest {

    @Test
    void mdcScopeDecoratorIsTheBraveMdcDecorator() {
        CurrentTraceContext.ScopeDecorator decorator = new MdcTracingConfig().mdcScopeDecorator();

        assertThat(decorator).isSameAs(MDCScopeDecorator.get());
    }
}
