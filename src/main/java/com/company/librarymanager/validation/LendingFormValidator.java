package com.company.librarymanager.validation;

import com.company.librarymanager.service.Dates;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Form-level checks for the issue-desk form.
 *
 * <p>Only checks that need no database are done here: that both dates are real
 * calendar days, that the due date is not before the issue date, and that the
 * loan period is not absurdly long. Whether the book has a copy free and
 * whether the member is under their limit are business rules that need
 * queries, so they belong in the service and come back as
 * {@link com.company.librarymanager.service.LibraryException}.
 */
public final class LendingFormValidator {

    /** Beyond this, a loan is almost certainly a typo and is refused outright. */
    public static final int MAX_LOAN_DAYS = 365;

    private LendingFormValidator() {
    }

    public static LendingFormValidator of() {
        return new LendingFormValidator();
    }

    public Map<String, String> validate(String bookId, String memberId, String dueDate) {
        Map<String, String> errors = new LinkedHashMap<>();

        if (bookId == null || bookId.isBlank()) {
            errors.put("bookId", "Choose a book");
        }
        if (memberId == null || memberId.isBlank()) {
            errors.put("memberId", "Choose a member");
        }

        if (dueDate == null || dueDate.isBlank()) {
            errors.put("dueDate", "Choose a due date");
            return errors;
        }

        LocalDate due = Dates.parseOrNull(dueDate);
        if (due == null) {
            errors.put("dueDate", "Enter a real date, as yyyy-mm-dd");
            return errors;
        }
        // A loan due before it is issued cannot be returned on time, so it is
        // refused rather than marked overdue on the spot.
        if (due.isBefore(LocalDate.now())) {
            errors.put("dueDate", "The due date cannot be in the past");
            return errors;
        }
        if (due.isAfter(LocalDate.now().plusDays(MAX_LOAN_DAYS))) {
            errors.put("dueDate", "A loan cannot run longer than %d days".formatted(MAX_LOAN_DAYS));
        }
        return errors;
    }
}
