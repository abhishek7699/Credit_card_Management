package com.creditcard.booking.controller.dto;

import com.creditcard.booking.domain.ChargeType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChargeResponseTest {

    @Test
    void accessors_returnConstructedValues() {
        UUID chargeId = UUID.randomUUID();
        Instant createdAt = Instant.now();

        ChargeResponse response = new ChargeResponse(chargeId, ChargeType.LAUNDRY, "Dry cleaning",
                new BigDecimal("12.00"), createdAt);

        assertThat(response.chargeId()).isEqualTo(chargeId);
        assertThat(response.type()).isEqualTo(ChargeType.LAUNDRY);
        assertThat(response.description()).isEqualTo("Dry cleaning");
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("12.00"));
        assertThat(response.createdAt()).isEqualTo(createdAt);
    }
}
