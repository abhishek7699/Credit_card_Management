package com.creditcard.booking.service;

import com.creditcard.booking.exception.BookingWindowException;
import com.creditcard.booking.exception.InvalidDateRangeException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnlineBookingPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private final OnlineBookingPolicy policy = new OnlineBookingPolicy(14, 365);

    @Test
    void ordinaryStay_passes() {
        assertThatCode(() -> policy.validate(TODAY.plusDays(1), TODAY.plusDays(3), TODAY)).doesNotThrowAnyException();
    }

    @Test
    void stayOfExactlyTheMaximum_passes() {
        assertThatCode(() -> policy.validate(TODAY, TODAY.plusDays(14), TODAY)).doesNotThrowAnyException();
    }

    @Test
    void stayOneNightOverTheMaximum_rejected() {
        assertThatThrownBy(() -> policy.validate(TODAY, TODAY.plusDays(15), TODAY))
                .isInstanceOf(BookingWindowException.class)
                .hasMessageContaining("at most 14 nights")
                .hasMessageContaining("requested 15");
    }

    @Test
    void checkInToday_passes() {
        assertThatCode(() -> policy.validate(TODAY, TODAY.plusDays(1), TODAY)).doesNotThrowAnyException();
    }

    @Test
    void checkInYesterday_rejected() {
        assertThatThrownBy(() -> policy.validate(TODAY.minusDays(1), TODAY, TODAY))
                .isInstanceOf(BookingWindowException.class)
                .hasMessageContaining("cannot be before today");
    }

    @Test
    void checkInExactlyMaxDaysAhead_passes() {
        assertThatCode(() -> policy.validate(TODAY.plusDays(365), TODAY.plusDays(366), TODAY))
                .doesNotThrowAnyException();
    }

    @Test
    void checkInOneDayBeyondMaxDaysAhead_rejected() {
        assertThatThrownBy(() -> policy.validate(TODAY.plusDays(366), TODAY.plusDays(367), TODAY))
                .isInstanceOf(BookingWindowException.class)
                .hasMessageContaining("at most 365 days ahead");
    }

    @Test
    void checkOutNotAfterCheckIn_isInvalidDateRange_notWindowViolation() {
        assertThatThrownBy(() -> policy.validate(TODAY, TODAY, TODAY))
                .isInstanceOf(InvalidDateRangeException.class);
    }

    @Test
    void configuredLimits_areTheOnesApplied() {
        OnlineBookingPolicy strict = new OnlineBookingPolicy(3, 30);

        assertThat(strict.getMaxStayNights()).isEqualTo(3);
        assertThat(strict.getMaxDaysAhead()).isEqualTo(30);
        assertThatThrownBy(() -> strict.validate(TODAY, TODAY.plusDays(4), TODAY))
                .isInstanceOf(BookingWindowException.class);
        assertThatThrownBy(() -> strict.validate(TODAY.plusDays(31), TODAY.plusDays(32), TODAY))
                .isInstanceOf(BookingWindowException.class);
    }
}
