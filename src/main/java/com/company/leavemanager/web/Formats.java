package com.company.leavemanager.web;

import com.company.leavemanager.domain.LeaveRequest;
import com.company.leavemanager.domain.RequestStatus;
import com.company.leavemanager.service.LeaveCalculator;
import com.company.leavemanager.service.LeaveService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Display helpers, exposed to Thymeleaf as {@code #fmt}.
 *
 * <p>Dates render in the browser's locale but a leave request is a calendar
 * range, so the stored {@code DATE} is never shifted across a timezone
 * boundary. Timestamps are audit events and do convert, because an event's
 * real time is what matters.
 *
 * <p>All methods are static; the instance exists only so Thymeleaf can reach
 * them through a model attribute.
 */
public final class Formats {

    /** Public so it can be instantiated as a bean for the model attribute. */
    public Formats() {
    }

    public static String days(BigDecimal value) {
        return LeaveCalculator.formatDays(value);
    }

    /** "5", "0.5". */
    public static String days(double value) {
        return LeaveCalculator.formatDays(BigDecimal.valueOf(value));
    }

    public static String date(LocalDate value) {
        return value == null ? "" : value.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
    }

    /** "9 Jun 2026 to 13 Jun 2026", or a single date when they match. */
    public static String dateRange(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            return "";
        }
        return start.equals(end) ? date(start) : date(start) + " to " + date(end);
    }

    /** "9 Jun 2026 (afternoon half-day)". */
    public static String requestRange(LeaveRequest request) {
        String range = dateRange(request.getStartDate(), request.getEndDate());
        if (request.isHalfDay() && request.getPartOfDay() != null) {
            return range + " (" + request.getPartOfDay().name().toLowerCase(Locale.ENGLISH) + " half-day)";
        }
        return range;
    }

    /** "9 Jun 2026, 14:30". */
    public static String timestamp(Instant value) {
        if (value == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
                .format(value.atZone(ZoneId.systemDefault()));
    }

    public static String status(RequestStatus status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case PENDING -> "Pending";
            case APPROVED -> "Approved";
            case REJECTED -> "Rejected";
        };
    }

    /** Tail of an id, enough to match it against a log line. */
    public static String shortId(String id) {
        if (id == null) {
            return "";
        }
        return id.length() <= 6 ? id : id.substring(id.length() - 6);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Audit metadata as display chips, e.g. {@code ["decision = APPROVED"]}.
     *
     * <p>Parsed as JSON rather than split on punctuation, because the stored
     * values are free text and may themselves contain commas and quotes.
     * Anything unparseable is shown as-is rather than dropped, so a log line
     * is never silently hidden from HR.
     */
    public static List<String> metadataChips(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = JSON.readTree(json);
            List<String> chips = new ArrayList<>();
            if (root.isObject()) {
                for (Map.Entry<String, JsonNode> property : root.properties()) {
                    JsonNode value = property.getValue();
                    chips.add(property.getKey() + " = "
                            + (value.isValueNode() ? value.asText() : value.toString()));
                }
            } else if (root.isValueNode()) {
                chips.add(root.asText());
            }
            return chips;
        } catch (JsonProcessingException ex) {
            return List.of(json);
        }
    }

/**
     * Meter tone for a balance tile: {@code rose} when nothing is left,
     * {@code amber} when something is reserved and the remainder is low.
     *
     * <p>The threshold lives here rather than in the template because SpEL
     * cannot call {@code BigDecimal.valueOf} unambiguously, and the rule is
     * business logic that a view should not be restating.
     */
    public static String meterTone(LeaveService.BalanceView balance) {
        BigDecimal remaining = balance.remaining();
        if (remaining.signum() <= 0) {
            return "rose";
        }
        if (balance.pending().signum() > 0
                && remaining.compareTo(BigDecimal.valueOf(2)) <= 0) {
            return "amber";
        }
        return "";
    }

    /**
     * Badge class for a request status, e.g. {@code badge-pending}.
 *
     <p>Kept here because SpEL cannot nest {@code ${...}} inside a larger
     * expression, so building the string in a view would need a workaround.
     */
    public static String statusClass(RequestStatus status) {
        return "badge badge-" + status.name().toLowerCase(Locale.ENGLISH);
    }

    /** Percentage of the entitlement consumed, clamped to 0-100. */
    public static int percent(BigDecimal entitled, BigDecimal used, BigDecimal pending) {
        if (entitled == null || entitled.signum() <= 0) {
            return 0;
        }
        BigDecimal consumed = used.add(pending);
        int value = consumed.multiply(BigDecimal.valueOf(100))
                .divide(entitled, 0, java.math.RoundingMode.DOWN)
                .intValue();
        return Math.max(0, Math.min(100, value));
    }
}
