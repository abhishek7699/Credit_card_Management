package com.creditcard.booking.repository;

import com.creditcard.booking.domain.Charge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChargeRepository extends JpaRepository<Charge, UUID> {

    /** Line items for the booking detail view, oldest first (contracts/api.md). */
    List<Charge> findByBookingIdOrderByCreatedAtAsc(UUID bookingId);

    /** Bulk lookup for the room board's balance-due flag (RoomService, US2) --
     *  one query across every occupied room's booking rather than N+1. */
    List<Charge> findByBookingIdIn(List<UUID> bookingIds);
}
