package com.company.librarymanager.web;

import com.company.librarymanager.domain.Book;
import com.company.librarymanager.domain.BookIssue;
import com.company.librarymanager.domain.IssueStatus;
import com.company.librarymanager.domain.Member;
import com.company.librarymanager.domain.RequestStatus;
import com.company.librarymanager.service.FineCalculator;
import com.company.librarymanager.service.MemberService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Display helpers, exposed to Thymeleaf as {@code #fmt}.
 *
 * <p>Dates render in the browser's locale but a loan is a calendar range, so the
 * stored {@code DATE} is never shifted across a timezone boundary. Timestamps
 * are audit events and do convert, because an event's real time is what matters.
 *
 * <p>All methods are static; the instance exists only so Thymeleaf can reach
 * them through a model attribute.
 */
public final class Formats {

    /** Public so it can be instantiated as a bean for the model attribute. */
    public Formats() {
    }

    /** "9 Jun 2026", or an empty string for null. */
    public static String date(LocalDate value) {
        return value == null ? "" : value.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
    }

    /** "9 Jun 2026, 14:30". */
    public static String timestamp(Instant value) {
        if (value == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
                .format(value.atZone(ZoneId.systemDefault()));
    }

    /** "12.50", or an empty string for null. Always two decimal places. */
    public static String money(BigDecimal value) {
        return FineCalculator.formatMoney(value);
    }

    /** "12.50" prefixed with the currency mark. */
    public static String price(BigDecimal value) {
        String formatted = money(value);
        return formatted.isEmpty() ? "" : "₹" + formatted;
    }

    /** "3 days", "1 day", "today". Used in loan and copy-count captions. */
    public static String days(Long value) {
        if (value == null) {
            return "";
        }
        if (value == 0) {
            return "today";
        }
        return value == 1 ? "1 day" : value + " days";
    }

    /** "9 Jun 2026 to 23 Jun 2026", or a single date when they match. */
    public static String loanPeriod(BookIssue issue) {
        if (issue == null || issue.getDueDate() == null) {
            return "";
        }
        String start = date(issue.getIssueDate());
        String end = date(issue.getDueDate());
        return start.equals(end) ? start : start + " to " + end;
    }

    /**
     * Whole days a loan is late as of today, or zero when it is not late.
     *
     * <p>A loan that was already returned is measured against the day it came
     * back, not today, so a book returned two weeks ago does not appear to have
     * been growing in debt.
     */
    public static long daysOverdue(BookIssue issue) {
        return issue == null ? 0 : issue.daysOverdue(LocalDate.now());
    }

    /**
     * How overdue a loan reads: "3 days late", "due today", "11 days late".
     *
     * <p>Derived here rather than in the template because the wording depends on
     * three cases, and a view restating them is a view that can get one wrong.
     */
    public static String overdueLabel(BookIssue issue) {
        if (issue == null) {
            return "";
        }
        long days = daysOverdue(issue);
        if (days <= 0) {
            return issue.isOpen() && issue.getDueDate() != null
                    && issue.getDueDate().isEqual(LocalDate.now())
                    ? "due today"
                    : "";
        }
        return days == 1 ? "1 day late" : days + " days late";
    }

    /**
     * The name to show for a member.
     *
     * <p>Delegates to {@link MemberService#displayName} so the desk, the
     * dashboard and the audit summaries all pick the same fallback when a
     * member has no linked account.
     */
    public static String memberName(Member member) {
        return member == null ? "" : MemberService.displayName(member);
    }

    /** The email on a member's linked account, or an empty string. */
    public static String memberEmail(Member member) {
        return member == null || member.getUser() == null ? "" : member.getUser().getEmail();
    }

    /**
     * How a loan's deadline reads: "due in 3 days", "due today", "6 days late".
     *
     * <p>Complements {@link #overdueLabel}, which says nothing until a loan is
     * actually late. This one covers the whole range so a due date column can
     * use it alone.
     */
    public static String dueLabel(BookIssue issue) {
        if (issue == null || issue.getDueDate() == null) {
            return "";
        }
        if (!issue.isOpen()) {
            return issue.getReturnDate() != null ? "returned" : "closed";
        }
        LocalDate today = LocalDate.now();
        long days = ChronoUnit.DAYS.between(today, issue.getDueDate());
        if (days < 0) {
            long late = -days;
            return late == 1 ? "1 day late" : late + " days late";
        }
        if (days == 0) {
            return "due today";
        }
        return days == 1 ? "due tomorrow" : "due in " + days + " days";
    }

    /** Tone class for a due-date label, so lateness is coloured consistently. */
    public static String dueTone(BookIssue issue) {
        return issue == null || !issue.isOpen() ? "muted" : daysOverdue(issue) > 0 ? "rose" : "";
    }

    /** Badge class for a loan status, e.g. {@code badge-issued}. */
    public static String statusClass(IssueStatus status) {
        return "badge badge-" + status.name().toLowerCase(Locale.ENGLISH);
    }

    /** "Issued", "Returned", "Lost". */
    public static String status(IssueStatus status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case ISSUED -> "On loan";
            case RETURNED -> "Returned";
            case LOST -> "Lost";
        };
    }

    /**
     * Badge class for a request status, so a queue can be read at a glance.
     *
     * <p>Deliberately not {@link #statusClass}: that derives its class from the
     * loan enum's names, which happen to overlap for APPROVED but not for
     * REJECTED, and a rejected request borrowing a loan's green would say the
     * opposite of what happened.
     */
    public static String requestStatusClass(RequestStatus status) {
        if (status == null) {
            return "badge badge-muted";
        }
        return switch (status) {
            case PENDING -> "badge badge-pending";
            case APPROVED -> "badge badge-approved";
            case REJECTED -> "badge badge-lost";
        };
    }

    /** "Waiting", "Granted", "Turned down". */
    public static String requestStatus(RequestStatus status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case PENDING -> "Waiting";
            case APPROVED -> "Granted";
            case REJECTED -> "Turned down";
        };
    }

    /**
     * Copy-availability tone for a catalogue card: {@code rose} when nothing is
     * left, {@code amber} when only the last few remain, empty otherwise.
     */
    public static String availabilityTone(Book book) {
        if (book == null || !book.isActive()) {
            return "muted";
        }
        if (book.getAvailableCopies() <= 0) {
            return "rose";
        }
        return book.getAvailableCopies() <= 2 ? "amber" : "";
    }

/**
     * The shelf a title is filed under.
     *
     * <p>A book may be filed without a category, so this is reached for in
     * preference to reading {@code book.category.name} directly: the category is
     * genuinely optional and a missing one is a fact worth showing, not a null
     * to guard against at every call site.
     */
    public static String categoryName(Book book) {
        if (book == null || book.getCategory() == null) {
            return "Uncategorised";
        }
        return book.getCategory().getName();
    }

    /** "3 of 5 available", "none available", "all 5 available". */
    public static String availability(Book book) {
        if (book == null) {
            return "";
        }
        if (book.getAvailableCopies() <= 0) {
            return "none available";
        }
        if (book.getAvailableCopies() == book.getTotalCopies()) {
            return "all %d available".formatted(book.getTotalCopies());
        }
        return "%d of %d available".formatted(book.getAvailableCopies(), book.getTotalCopies());
    }

    /** "3 in stock", "1 in stock". */
    public static String copies(Integer value) {
        if (value == null) {
            return "";
        }
        return value == 1 ? "1 in stock" : value + " in stock";
    }

    /**
     * How long a book has been out, for a loan that is still open.
     *
     * <p>Counted to today for an open loan and to the return date for a closed
     * one, which is why this is not simply {@code daysOverdue}.
     */
    public static String heldFor(BookIssue issue) {
        if (issue == null || issue.getIssueDate() == null) {
            return "";
        }
        LocalDate end = issue.getReturnDate() != null ? issue.getReturnDate() : LocalDate.now();
        long days = ChronoUnit.DAYS.between(issue.getIssueDate(), end);
        return days <= 0 ? "issued today" : days == 1 ? "out 1 day" : "out " + days + " days";
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
     * Audit metadata as display chips, e.g. {@code ["dueDate = 2026-06-23"]}.
     *
     * <p>Parsed as JSON rather than split on punctuation, because the stored
     * values are free text and may themselves contain commas and quotes.
     * Anything unparseable is shown as-is rather than dropped, so a log line is
     * never silently hidden from an administrator.
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
}
