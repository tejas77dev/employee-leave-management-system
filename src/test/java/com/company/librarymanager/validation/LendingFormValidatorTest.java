package com.company.librarymanager.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The checks the issue-desk form can make without touching the database.
 *
 * <p>Whether a copy is free and whether a member is under their limit need
 * queries, so they belong to the service. What is left here is everything that
 * can be decided from the three fields alone.
 */
class LendingFormValidatorTest {

    private final LendingFormValidator validator = LendingFormValidator.of();

    @Test
    @DisplayName("accepts a well-formed loan")
    void acceptsAWellFormedLoan() {
        assertTrue(validator.validate("book-1", "member-1", future(7)).isEmpty());
    }

    @Test
    @DisplayName("insists on both a book and a member")
    void requiresABookAndAMember() {
        Map<String, String> errors = validator.validate("", "  ", future(7));
        assertEquals("Choose a book", errors.get("bookId"));
        assertEquals("Choose a member", errors.get("memberId"));
    }

    @Test
    @DisplayName("insists on a due date")
    void requiresADueDate() {
        assertEquals("Choose a due date", validator.validate("book-1", "member-1", "").get("dueDate"));
        assertEquals("Choose a due date", validator.validate("book-1", "member-1", null).get("dueDate"));
    }

    @Test
    @DisplayName("refuses a due date already past, rather than issuing it overdue")
    void refusesAPastDueDate() {
        assertEquals("The due date cannot be in the past",
                validator.validate("book-1", "member-1", LocalDate.now().minusDays(1).toString()).get("dueDate"));
    }

    @Test
    @DisplayName("accepts a book due back today, which is not late yet")
    void acceptsTodayAsDueDate() {
        assertTrue(validator.validate("book-1", "member-1", LocalDate.now().toString()).isEmpty());
    }

    @Test
    @DisplayName("refuses an unparseable date instead of guessing at one")
    void refusesAnUnparseableDate() {
        assertEquals("Enter a real date, as yyyy-mm-dd",
                validator.validate("book-1", "member-1", "next tuesday").get("dueDate"));
    }

    @Test
    @DisplayName("refuses a loan period long enough to be a typo")
    void refusesAnAbsurdLoanPeriod() {
        String message = validator.validate("book-1", "member-1",
                LocalDate.now().plusDays(LendingFormValidator.MAX_LOAN_DAYS + 1).toString()).get("dueDate");
        assertTrue(message != null && message.contains("365"), "expected the year limit, got: " + message);
    }

    @Test
    @DisplayName("accepts a loan right at the limit")
    void acceptsTheLimitItself() {
        assertFalse(validator.validate("book-1", "member-1",
                LocalDate.now().plusDays(LendingFormValidator.MAX_LOAN_DAYS).toString())
                .containsKey("dueDate"));
    }

    private static String future(int days) {
        return LocalDate.now().plusDays(days).toString();
    }
}