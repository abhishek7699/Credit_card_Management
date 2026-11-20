package com.creditcard.booking.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "rooms")
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "room_number", nullable = false, length = 20)
    private String roomNumber;

    @Column(name = "room_type", nullable = false, length = 50)
    private String roomType;

    @Column(name = "nightly_rate", nullable = false)
    private BigDecimal nightlyRate;

    @Column(name = "floor", nullable = false)
    private Integer floor;

    @Column(name = "position", nullable = false)
    private Integer position;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RoomStatus status = RoomStatus.VACANT_CLEAN;

    /** What a guest gets with this room, e.g. "Wi-Fi", "Breakfast" (V10). Eager: it is
     *  a short list read alongside every room, and the board/availability reads run
     *  outside any open session. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "room_inclusions", joinColumns = @JoinColumn(name = "room_id"))
    @Column(name = "item", nullable = false, length = 50)
    private List<String> included = new ArrayList<>();

    public Room() {}

    public UUID getId() { return id; }

    public String getRoomNumber() { return roomNumber; }
    public void setRoomNumber(String roomNumber) { this.roomNumber = roomNumber; }

    public String getRoomType() { return roomType; }
    public void setRoomType(String roomType) { this.roomType = roomType; }

    public BigDecimal getNightlyRate() { return nightlyRate; }
    public void setNightlyRate(BigDecimal nightlyRate) { this.nightlyRate = nightlyRate; }

    public Integer getFloor() { return floor; }
    public void setFloor(Integer floor) { this.floor = floor; }

    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }

    public List<String> getIncluded() { return List.copyOf(included); }
    public void setIncluded(List<String> included) { this.included = new ArrayList<>(included); }

    public RoomStatus getStatus() { return status; }
    public void setStatus(RoomStatus status) { this.status = status; }
}
