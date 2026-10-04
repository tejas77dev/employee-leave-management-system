package com.company.librarymanager.validation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Form-level checks for the book editor, before any database work.
 *
 * <p>Kept separate from the service so a bad entry is reported against the
 * field that caused it without opening a transaction or touching a repository.
 * Returns a field-to-message map rather than throwing, because a form needs to
 * show every problem at once rather than one per submission.
 *
 * <p>Immutable and stateless; {@link #of()} exists so a caller reads as
 * {@code IsbnBookValidator.of().validate(...)} at the call site.
 */
public final class BookFormValidator {

    private BookFormValidator() {
    }

    public static BookFormValidator of() {
        return new BookFormValidator();
    }

    public static final int MAX_TITLE = 255;
    public static final int MAX_AUTHOR = 255;
    public static final int MAX_COPIES = 500;

    /**
     * @param id blank for a new book, set when editing one
     */
    public Map<String, String> validate(String id,
                                        String isbn,
                                        String title,
                                        String author,
                                        String publisher,
                                        String totalCopies,
                                        String publishedYear,
                                        String rackNo) {
        Map<String, String> errors = new LinkedHashMap<>();

        if (isbn == null || isbn.isBlank()) {
            errors.put("isbn", Isbn.describe());
        } else if (!Isbn.isValid(isbn)) {
            errors.put("isbn", Isbn.describe());
        }

        if (title == null || title.isBlank()) {
            errors.put("title", "Enter a title");
        } else if (title.trim().length() > MAX_TITLE) {
            errors.put("title", "Keep the title under %d characters".formatted(MAX_TITLE));
        }

        if (author == null || author.isBlank()) {
            errors.put("author", "Enter an author");
        } else if (author.trim().length() > MAX_AUTHOR) {
            errors.put("author", "Keep the author under %d characters".formatted(MAX_AUTHOR));
        }

        Integer copies = parseInt(totalCopies);
        if (copies == null) {
            // An empty field must not silently become a catalogue entry with no
            // copies, which would then be invisible on the shelf.
            errors.put("totalCopies", "Enter how many copies are held");
        } else if (copies < 1) {
            errors.put("totalCopies", "A book needs at least one copy");
        } else if (copies > MAX_COPIES) {
            errors.put("totalCopies", "That seems too high");
        }

        if (publishedYear != null && !publishedYear.isBlank()) {
            Integer year = parseInt(publishedYear);
            // The upper bound is deliberately generous; this guards against a
            // mistyped year such as 20996, not against an old book.
            if (year == null || year < 1450 || year > 2200) {
                errors.put("publishedYear", "Enter a year between 1450 and 2200");
            }
        }

        if (publisher != null && publisher.length() > MAX_TITLE) {
            errors.put("publisher", "Keep the publisher under %d characters".formatted(MAX_TITLE));
        }
        if (rackNo != null && rackNo.length() > 50) {
            errors.put("rackNo", "Keep the rack under 50 characters");
        }

        return errors;
    }

    private static Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * The copy count from the form, or null when the field is empty or not a
     * number.
     *
     * <p>Exposed so a controller can hand the service an {@code Integer} only
     * after the field has been shown to be sound, rather than passing on a value
     * the validator has not yet accepted.
     */
    public static Integer copies(String value) {
        return parseInt(value);
    }
}
