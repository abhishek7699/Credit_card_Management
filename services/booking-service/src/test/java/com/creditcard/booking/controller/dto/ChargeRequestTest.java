package com.creditcard.booking.controller.dto;

import com.creditcard.booking.domain.ChargeType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ChargeRequestTest {

    @Test
    void accessors_returnConstructedValues() {
        ChargeRequest request = new ChargeRequest(ChargeType.MINIBAR, "Sparkling water", new BigDecimal("6.00"));

        assertThat(request.type()).isEqualTo(ChargeType.MINIBAR);
        assertThat(request.description()).isEqualTo("Sparkling water");
        assertThat(request.amount()).isEqualByComparingTo(new BigDecimal("6.00"));
    }
}
