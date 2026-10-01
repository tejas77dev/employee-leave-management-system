package com.company.leavemanager.validation;

import com.company.leavemanager.service.LeaveCalculator;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Form checks for a leave request.
 *
 * <p>Most of these are cross-field rules, which annotation-based validation
 * cannot express, so they are checked together and reported per field.
 */
public final class LeaveRequestValidator {

    private final Map<String, String> errors = new LinkedHashMap<>();

    public static LeaveRequestValidator of() {
        return new LeaveRequestValidator();
    }

    /**
     * Checks the submitted values.
     *
     * @param partOfDay the chosen half, or null when the control was absent
     * @return the first message found for each field, keyed by field name
     */
    public Map<String, String> validate(String leaveTypeId,
                                        String startDate,
                                        String endDate,
                                        boolean halfDay,
                                        String partOfDay,
                                        String reason) {
        if (leaveTypeId == null || leaveTypeId.isBlank()) {
            errors.put("leaveTypeId", "Choose a leave type");
        }

        LocalDateFields parsed = parseDates(startDate, endDate);
        if (parsed.start() == null || parsed.end() == null) {
            return errors;
        }

        if (halfDay) {
            // A half-day must name its half, sit on a working day, and cover
            // exactly one date.
            if (partOfDay == null || partOfDay.isBlank()) {
                errors.put("partOfDay", "Choose morning or afternoon");
            } else if (!"MORNING".equals(partOfDay) && !"AFTERNOON".equals(partOfDay)) {
                errors.put("partOfDay", "Choose morning or afternoon");
            }
            if (!parsed.start().equals(parsed.end())) {
                errors.put("endDate", "A half-day must fall on a single date");
            }
            if (LeaveCalculator.isWeekend(parsed.start())) {
                errors.put("startDate", "A weekend date has no working half to take");
            }
        } else {
            // A full-day request must not carry a half, and cannot end early.
            if (partOfDay != null && !partOfDay.isBlank()) {
                errors.put("partOfDay", "Only half-day requests can select a half");
            }
            if (parsed.end().isBefore(parsed.start())) {
                errors.put("endDate", "End date must not be before the start date");
            }
        }

        if (reason != null && reason.length() > 500) {
            errors.put("reason", "Keep the reason under 500 characters");
        }
        return errors;
    }

    private record LocalDateFields(java.time.LocalDate start, java.time.LocalDate end) {
    }

    /**
     * Parses both dates, reporting each independently so one bad date does not
     * hide the other.
     */
    private LocalDateFields parseDates(String startDate, String endDate) {
        java.time.LocalDate start = null;
        java.time.LocalDate end = null;

        if (startDate == null || startDate.isBlank()) {
            errors.put("startDate", "Choose a start date");
        } else {
            try {
                start = LeaveCalculator.parse(startDate);
            } catch (IllegalArgumentException ex) {
                errors.put("startDate", "Choose a start date");
            }
        }

        if (endDate == null || endDate.isBlank()) {
            errors.put("endDate", "Choose an end date");
        } else {
            try {
                end = LeaveCalculator.parse(endDate);
            } catch (IllegalArgumentException ex) {
                errors.put("endDate", "Choose an end date");
            }
        }

        return new LocalDateFields(start, end);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** Days the request would consume, or null if the range is not yet valid. */
    public static BigDecimal previewDays(java.time.LocalDate start,
                                          java.time.LocalDate end,
                                          boolean halfDay) {
        try {
            return LeaveCalculator.calculateDays(start, end, halfDay);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
