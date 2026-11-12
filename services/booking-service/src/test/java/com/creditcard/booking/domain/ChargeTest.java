package com.creditcard.booking.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChargeTest {

    @Test
    void onCreate_setsCreatedAt() {
        Charge charge = new Charge();
        charge.onCreate();

        assertThat(charge.getCreatedAt()).isNotNull();
    }

    @Test
    void gettersAndSetters() {
        Charge charge = new Charge();
        UUID bookingId = UUID.randomUUID();

        charge.setBookingId(bookingId);
        charge.setType(ChargeType.ROOM_SERVICE);
        charge.setDescription("Club sandwich");
        charge.setAmount(new BigDecimal("18.50"));

        assertThat(charge.getBookingId()).isEqualTo(bookingId);
        assertThat(charge.getType()).isEqualTo(ChargeType.ROOM_SERVICE);
        assertThat(charge.getDescription()).isEqualTo("Club sandwich");
        assertThat(charge.getAmount()).isEqualByComparingTo(new BigDecimal("18.50"));
        assertThat(charge.getId()).isNull();
    }
}
