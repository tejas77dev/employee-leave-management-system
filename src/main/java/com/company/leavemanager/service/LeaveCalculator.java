package com.company.leavemanager.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * Leave arithmetic, with no database or web dependencies.
 *
 * <p>Weekends are free, so a Monday-to-Friday request costs 5 days. A half-day
 * always costs exactly 0.5 and must cover a single date.
 *
 * <p>Day counts are {@link BigDecimal}, not {@code double}. Half-days make
 * 0.5 and 0.5 add up, and binary floating point cannot represent 0.1 exactly,
 * so a double would drift to something like 0.30000000000000004 after a few
 * requests and slowly corrupt a balance.
 */
public final class LeaveCalculator {

    /**
     * Strict ISO date. ResolverStyle.STRICT means {@code 2026-02-30} and a
     * non-existent 29 February are rejected rather than rolled into March.
     */
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    public static final BigDecimal HALF_DAY = new BigDecimal("0.5");

    private LeaveCalculator() {
    }

    /** Formats a date for storage and for the {@code yyyy-MM-dd} form fields. */
    public static String format(LocalDate date) {
        return DATE_FORMAT.format(date);
    }

    /**
     * Parses a form date. Throws {@link IllegalArgumentException} on anything
     * that is not a real calendar date, so callers can report it as a
     * validation error rather than a server error.
     */
    public static LocalDate parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Invalid date format: null");
        }
        try {
            return LocalDate.parse(value.trim(), DATE_FORMAT);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid date: " + value);
        }
    }

    public static boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    /** Counts Monday-to-Friday days in an inclusive range. */
    public static int countWeekdays(LocalDate start, LocalDate end) {
        requireOrdered(start, end);
        long count = 0;
        for (LocalDate cursor = start; !cursor.isAfter(end); cursor = cursor.plusDays(1)) {
            if (!isWeekend(cursor)) {
                count++;
            }
        }
        return (int) count;
    }

    /**
     * Days consumed by a request.
     *
     * <p>A half-day is always 0.5, and must sit on a single date. Everything
     * else counts weekdays, so weekends inside the range are free.
     */
    public static BigDecimal calculateDays(LocalDate start, LocalDate end, boolean halfDay) {
        requireOrdered(start, end);

        if (halfDay) {
            if (!start.equals(end)) {
                throw new IllegalArgumentException("A half-day request must cover a single date");
            }
            return HALF_DAY;
        }

        return BigDecimal.valueOf(countWeekdays(start, end));
    }

    /**
     * Days still available: entitlement not yet used or reserved.
     *
     * <p>Can go negative if usage exceeds the entitlement, and the UI shows
     * that rather than hiding it.
     */
    public static BigDecimal remaining(BigDecimal entitled, BigDecimal used, BigDecimal pending) {
        return entitled.subtract(used).subtract(pending);
    }

    /**
     * Whether two inclusive date ranges share at least one day.
     *
     * <p>Both ends are inclusive, so two requests that meet on a single day
     * count as overlapping.
     */
    public static boolean rangesOverlap(LocalDate aStart, LocalDate aEnd, LocalDate bStart, LocalDate bEnd) {
        return !aStart.isAfter(bEnd) && !bStart.isAfter(aEnd);
    }

    /** Rounds to the half-day grid the database stores. */
    public static BigDecimal roundDays(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** Formats a day count the way the UI shows it: 5, not 5.0; 0.5, not 0.50. */
    public static String formatDays(BigDecimal days) {
        if (days == null) {
            return "";
        }
        BigDecimal stripped = days.stripTrailingZeros();
        return stripped.scale() <= 0 ? stripped.toBigInteger().toString() : stripped.toPlainString();
    }

    public static int currentYear() {
        return LocalDate.now().getYear();
    }

    public static LocalDate startOfYear(int year) {
        return LocalDate.of(year, 1, 1);
    }

    public static LocalDate endOfYear(int year) {
        return LocalDate.of(year, 12, 31);
    }

    private static void requireOrdered(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("End date must not be before start date");
        }
    }
}
