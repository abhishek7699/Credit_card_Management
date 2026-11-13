package com.creditcard.authorization.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TimeoutMarkerTest {

    @Test
    void exposesTheTransactionAndTheTimeWhenItWasStored() {
        UUID txId = UUID.randomUUID();
        TimeoutMarker marker = new TimeoutMarker(txId);

        marker.onCreate();

        assertThat(marker.getTransactionId()).isEqualTo(txId);
        assertThat(marker.getMarkedAt()).isNotNull();
    }

    @Test
    void hasAnEmptyConstructorForJpa() {
        assertThat(new TimeoutMarker().getTransactionId()).isNull();
    }
}
