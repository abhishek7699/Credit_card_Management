package com.creditcard.gateway.tracing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MdcTracingConfigTest {

    @Test
    void registersTheMdcScopeDecorator() {
        assertThat(new MdcTracingConfig().mdcScopeDecorator()).isNotNull();
    }
}
