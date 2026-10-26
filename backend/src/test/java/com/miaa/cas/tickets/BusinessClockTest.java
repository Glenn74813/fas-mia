package com.miaa.cas.tickets;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BusinessClockTest {

    private static final ZoneId MNL = ZoneId.of("Asia/Manila");
    private final BusinessClock clock = new BusinessClock(MNL, LocalTime.of(8, 0), LocalTime.of(17, 0),
            EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), Set.of(LocalDate.of(2026, 11, 2))::contains);

    private static Instant at(int month, int day, int hour, int minute) {
        return LocalDateTime.of(2026, month, day, hour, minute).atZone(MNL).toInstant();
    }

    @Test
    void businessMinutesSkipNightsWeekendsAndHolidays() {
        // Friday 30 Oct 16:00 + 2 business hours: one hour Friday, then Monday 2 Nov is a holiday -> Tuesday 09:00.
        assertThat(clock.plus(at(10, 30, 16, 0), 120, true)).isEqualTo(at(11, 3, 9, 0));
        // Before opening, the clock starts at 08:00.
        assertThat(clock.plus(at(10, 29, 6, 30), 30, true)).isEqualTo(at(10, 29, 8, 30));
        // 24x7 counts every minute.
        assertThat(clock.plus(at(10, 30, 16, 0), 120, false)).isEqualTo(at(10, 30, 18, 0));
    }

    @Test
    void elapsedBusinessMinutes() {
        assertThat(clock.minutesBetween(at(10, 30, 16, 0), at(11, 3, 9, 0), true)).isEqualTo(120);
        assertThat(clock.minutesBetween(at(10, 31, 10, 0), at(11, 1, 10, 0), true)).isZero();
        assertThat(clock.minutesBetween(at(11, 3, 9, 0), at(10, 30, 16, 0), true)).isZero();
        assertThat(clock.minutesBetween(at(10, 30, 16, 0), at(10, 30, 18, 0), false)).isEqualTo(120);
    }
}
