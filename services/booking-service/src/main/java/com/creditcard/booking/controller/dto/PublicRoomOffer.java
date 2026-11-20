package com.creditcard.booking.controller.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** One bookable room for a guest's date range. {@code total} is server-computed
 *  (nightlyRate x nights); the client never supplies a price. */
public record PublicRoomOffer(
        UUID roomId,
        String roomNumber,
        String roomType,
        BigDecimal nightlyRate,
        BigDecimal total,
        List<String> included
) {
}
