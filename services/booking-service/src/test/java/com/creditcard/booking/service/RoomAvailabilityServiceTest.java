package com.creditcard.booking.service;

import com.creditcard.booking.domain.Room;
import com.creditcard.booking.exception.InvalidDateRangeException;
import com.creditcard.booking.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomAvailabilityServiceTest {

    @Mock
    private RoomRepository roomRepository;

    private RoomAvailabilityService service;

    @BeforeEach
    void setUp() {
        service = new RoomAvailabilityService(roomRepository);
    }

    @Test
    void validRange_delegatesToRepositoryAndReturnsAvailableRooms() {
        LocalDate checkIn = LocalDate.of(2026, 11, 1);
        LocalDate checkOut = LocalDate.of(2026, 11, 3);
        Room room = new Room();
        when(roomRepository.findAvailable(checkIn, checkOut)).thenReturn(List.of(room));

        List<Room> result = service.findAvailable(checkIn, checkOut);

        assertThat(result).containsExactly(room);
    }

    @Test
    void checkOutEqualsCheckIn_throwsInvalidDateRange() {
        LocalDate date = LocalDate.of(2026, 11, 1);

        assertThatThrownBy(() -> service.findAvailable(date, date))
                .isInstanceOf(InvalidDateRangeException.class);
    }

    @Test
    void checkOutBeforeCheckIn_throwsInvalidDateRange() {
        LocalDate checkIn = LocalDate.of(2026, 11, 3);
        LocalDate checkOut = LocalDate.of(2026, 11, 1);

        assertThatThrownBy(() -> service.findAvailable(checkIn, checkOut))
                .isInstanceOf(InvalidDateRangeException.class);
    }
}
