package com.creditcard.booking.controller.dto;

import com.creditcard.booking.domain.ChargeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** Submitted by front-desk staff against a CHECKED_IN booking (FR-001/FR-002). */
public record ChargeRequest(
        @NotNull ChargeType type,
        @NotBlank String description,
        @NotNull @Positive BigDecimal amount
) {
}
