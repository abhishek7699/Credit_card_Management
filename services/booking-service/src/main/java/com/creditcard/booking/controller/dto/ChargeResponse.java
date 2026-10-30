package com.creditcard.booking.controller.dto;

import com.creditcard.booking.domain.ChargeType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ChargeResponse(
        UUID chargeId,
        ChargeType type,
        String description,
        BigDecimal amount,
        Instant createdAt
) {
}
