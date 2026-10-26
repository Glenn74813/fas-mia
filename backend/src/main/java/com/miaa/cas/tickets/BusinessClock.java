package com.miaa.cas.tickets;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.function.Predicate;

/**
 * SLA clock. A 24x7 policy counts every minute; a business-hours policy counts only minutes inside the
 * working window on working days that are not holidays. Hours, days and holidays are configuration (C-15).
 */
public final class BusinessClock {

    private static final int MAX_DAYS = 3660;

    private final ZoneId zone;
    private final LocalTime start;
    private final LocalTime end;
    private final Set<DayOfWeek> workdays;
    private final Predicate<LocalDate> holiday;

    public BusinessClock(ZoneId zone, LocalTime start, LocalTime end, Set<DayOfWeek> workdays, Predicate<LocalDate> holiday) {
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("Business hours must end after they start.");
        }
        this.zone = zone;
        this.start = start;
        this.end = end;
        this.workdays = workdays;
        this.holiday = holiday;
    }

    /** The instant {@code minutes} SLA minutes after {@code from}. */
    public Instant plus(Instant from, long minutes, boolean business) {
        if (!business) {
            return from.plus(Duration.ofMinutes(minutes));
        }
        long remaining = minutes;
        ZonedDateTime t = from.atZone(zone);
        for (int i = 0; i < MAX_DAYS; i++) {
            LocalDate day = t.toLocalDate();
            ZonedDateTime open = day.atTime(start).atZone(zone);
            ZonedDateTime close = day.atTime(end).atZone(zone);
            if (!working(day) || !t.isBefore(close)) {
                t = day.plusDays(1).atTime(start).atZone(zone);
                continue;
            }
            if (t.isBefore(open)) {
                t = open;
            }
            long available = Duration.between(t, close).toMinutes();
            if (remaining <= available) {
                return t.plusMinutes(remaining).toInstant();
            }
            remaining -= available;
            t = day.plusDays(1).atTime(start).atZone(zone);
        }
        throw new IllegalStateException("No working time found within ten years; check the business calendar.");
    }

    /** SLA minutes elapsed from {@code from} to {@code to} (0 when {@code to} is not after {@code from}). */
    public long minutesBetween(Instant from, Instant to, boolean business) {
        if (!to.isAfter(from)) {
            return 0;
        }
        if (!business) {
            return Duration.between(from, to).toMinutes();
        }
        long total = 0;
        LocalDate day = from.atZone(zone).toLocalDate();
        LocalDate last = to.atZone(zone).toLocalDate();
        for (int i = 0; i < MAX_DAYS && !day.isAfter(last); i++, day = day.plusDays(1)) {
            if (!working(day)) {
                continue;
            }
            Instant open = day.atTime(start).atZone(zone).toInstant();
            Instant close = day.atTime(end).atZone(zone).toInstant();
            Instant a = from.isAfter(open) ? from : open;
            Instant b = to.isBefore(close) ? to : close;
            if (b.isAfter(a)) {
                total += Duration.between(a, b).toMinutes();
            }
        }
        return total;
    }

    private boolean working(LocalDate day) {
        return workdays.contains(day.getDayOfWeek()) && !holiday.test(day);
    }
}
