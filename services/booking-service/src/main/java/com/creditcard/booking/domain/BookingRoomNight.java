package com.creditcard.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One row per night of a booking's stay. The {@code uq_booking_room_nights_room_date}
 * unique constraint on (room_id, stay_date) is the sole mechanism guaranteeing at most
 * one HELD/CONFIRMED booking per room per night (FR-007, data-model.md).
 */
@Entity
@Table(name = "booking_room_nights")
public class BookingRoomNight {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "stay_date", nullable = false)
    private LocalDate stayDate;

    public BookingRoomNight() {}

    public BookingRoomNight(UUID bookingId, UUID roomId, LocalDate stayDate) {
        this.bookingId = bookingId;
        this.roomId = roomId;
        this.stayDate = stayDate;
    }

    public UUID getId() { return id; }

    public UUID getBookingId() { return bookingId; }

    public UUID getRoomId() { return roomId; }

    public LocalDate getStayDate() { return stayDate; }
}
