package com.creditcard.reporting.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionTest {

    @Test
    void resourceNotFoundException_storesMessage() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Settlement batch not found: abc-123");
        assertThat(ex.getMessage()).isEqualTo("Settlement batch not found: abc-123");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void invalidDateRangeException_storesMessage() {
        InvalidDateRangeException ex = new InvalidDateRangeException("Date range exceeds maximum of 90 days");
        assertThat(ex.getMessage()).isEqualTo("Date range exceeds maximum of 90 days");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
