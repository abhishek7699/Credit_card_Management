package com.creditcard.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "guest_name", nullable = false, length = 200)
    private String guestName;

    @Column(name = "guest_phone", nullable = false, length = 30)
    private String guestPhone;

    @Column(name = "guest_email", length = 320)
    private String guestEmail;

    @Column(name = "visiting_from", length = 200)
    private String visitingFrom;

    /** Defaults to 1, mirroring the column's own DB default and the {@code adults >= 1}
     *  CHECK constraint -- so a {@code Booking} built directly (not only through
     *  {@code BookingService#createConfirmedBooking}'s own explicit 1/0 defaulting,
     *  FR-018) never violates it by simply being left unset. */
    @Column(name = "adults", nullable = false)
    private int adults = 1;

    @Column(name = "children", nullable = false)
    private int children;

    @Column(name = "check_in", nullable = false)
    private LocalDate checkIn;

    @Column(name = "check_out", nullable = false)
    private LocalDate checkOut;

    @Column(name = "expected_arrival_time")
    private LocalTime expectedArrivalTime;

    @Column(name = "expected_departure_time")
    private LocalTime expectedDepartureTime;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_reason", length = 30)
    private CancellationReason cancellationReason;

    /** Non-null only on a booking created under the pre-rework model, meaning its room
     *  cost was already paid at booking time (research.md §2/§8). Nothing sets this on
     *  a booking created from the 2026-10-31 rework forward -- it is a pure legacy
     *  marker, read by {@link com.creditcard.booking.service.BookingService#getBalanceDue}
     *  to avoid double-charging a legacy booking for a room cost it already paid. */
    @Column(name = "legacy_room_payment_transaction_id", length = 100)
    private String legacyRoomPaymentTransactionId;

    /** The rate-locked quote for this stay -- nights x room.nightlyRate at the moment
     *  the booking was created, set by BookingService#createConfirmedBooking itself.
     *  Balance calculation reads this directly and never recomputes it from the room's
     *  current rate (research.md §2) -- a room-rate change after booking never moves an
     *  already-confirmed booking's balance. The column itself stays nullable: a stray
     *  pre-rework HELD row (now migrated to CANCELLED/MIGRATED_UNPAID_HOLD by V7) may
     *  have never reached this far if its original gRPC call failed outright -- its
     *  balance is never read, since it's terminal and CANCELLED. */
    @Column(name = "room_charge_amount")
    private BigDecimal roomChargeAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Booking() {}

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }

    public UUID getRoomId() { return roomId; }
    public void setRoomId(UUID roomId) { this.roomId = roomId; }

    public String getGuestName() { return guestName; }
    public void setGuestName(String guestName) { this.guestName = guestName; }

    public String getGuestPhone() { return guestPhone; }
    public void setGuestPhone(String guestPhone) { this.guestPhone = guestPhone; }

    public String getGuestEmail() { return guestEmail; }
    public void setGuestEmail(String guestEmail) { this.guestEmail = guestEmail; }

    public String getVisitingFrom() { return visitingFrom; }
    public void setVisitingFrom(String visitingFrom) { this.visitingFrom = visitingFrom; }

    public int getAdults() { return adults; }
    public void setAdults(int adults) { this.adults = adults; }

    public int getChildren() { return children; }
    public void setChildren(int children) { this.children = children; }

    public LocalDate getCheckIn() { return checkIn; }
    public void setCheckIn(LocalDate checkIn) { this.checkIn = checkIn; }

    public LocalDate getCheckOut() { return checkOut; }
    public void setCheckOut(LocalDate checkOut) { this.checkOut = checkOut; }

    public LocalTime getExpectedArrivalTime() { return expectedArrivalTime; }
    public void setExpectedArrivalTime(LocalTime expectedArrivalTime) { this.expectedArrivalTime = expectedArrivalTime; }

    public LocalTime getExpectedDepartureTime() { return expectedDepartureTime; }
    public void setExpectedDepartureTime(LocalTime expectedDepartureTime) { this.expectedDepartureTime = expectedDepartureTime; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public BookingStatus getStatus() { return status; }
    public void setStatus(BookingStatus status) { this.status = status; }

    public CancellationReason getCancellationReason() { return cancellationReason; }
    public void setCancellationReason(CancellationReason cancellationReason) { this.cancellationReason = cancellationReason; }

    public String getLegacyRoomPaymentTransactionId() { return legacyRoomPaymentTransactionId; }
    public void setLegacyRoomPaymentTransactionId(String legacyRoomPaymentTransactionId) { this.legacyRoomPaymentTransactionId = legacyRoomPaymentTransactionId; }

    public BigDecimal getRoomChargeAmount() { return roomChargeAmount; }
    public void setRoomChargeAmount(BigDecimal roomChargeAmount) { this.roomChargeAmount = roomChargeAmount; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
