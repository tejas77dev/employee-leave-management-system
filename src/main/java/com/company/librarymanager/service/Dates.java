package com.company.librarymanager.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * Strict ISO date handling, with no database or web dependencies.
 *
 * <p>A loan is a calendar range, not an instant: a book handed over on the 9th
 * is due back on the 23rd regardless of the hour or the server's time zone.
 * {@code ResolverStyle.STRICT} is what stops a lenient parse from silently
 * rolling 30 February into March and quietly inventing a due date nobody
 * agreed to.
 */
public final class Dates {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private Dates() {
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
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Invalid date format: " + value);
        }
        try {
            return LocalDate.parse(value.trim(), DATE_FORMAT);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid date: " + value);
        }
    }

    /** Parses a form date, or null when the text is not a real date. */
    public static LocalDate parseOrNull(String value) {
        try {
            return parse(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
