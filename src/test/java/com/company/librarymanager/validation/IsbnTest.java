package com.company.librarymanager.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ISBN rules, checked directly.
 *
 * <p>These cases are here because a validator that throws on empty input takes
 * the whole form down with it, and because the checksum is the only thing
 * standing between a mistyped digit and a catalogue that cannot find its own
 * stock.
 */
class IsbnTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "9780132350884",   // Clean Code
            "978-0-13-235088-4",
            "0132350882",      // ISBN-10 form of the same book
            "080442957X",      // ISBN-10 whose check digit is X
            "0-8044-2957-X"
    })
    @DisplayName("accepts well-formed codes")
    void acceptsValidIsbn(String isbn) {
        assertTrue(Isbn.isValid(isbn), isbn + " should be valid");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "9780132350885",   // right shape, wrong final digit
            "1234567890",
            "978013235088",    // one digit short
            "97801323508844",  // one digit long
            "X780132350884",   // X is not a digit
            "978X132350884"    // X anywhere but the last position
    })
    @DisplayName("rejects malformed and mistyped codes")
    void rejectsInvalidIsbn(String isbn) {
        assertFalse(Isbn.isValid(isbn), isbn + " should be invalid");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "-", "not-an-isbn"})
    @DisplayName("reads blank input as invalid rather than throwing")
    void rejectsBlankInputWithoutThrowing(String isbn) {
        // The bug this pins: normalise returns null for anything unrecognised,
        // and the caller used to call length() on it. A form validator that
        // crashes on an empty field takes the page with it.
        assertFalse(Isbn.isValid(isbn));
    }

    @Test
    @DisplayName("strips separators so a scanned code still matches")
    void normalisesSeparators() {
        assertTrue("9780132350884".equals(Isbn.normalise("978-0-13-235088-4")));
        assertTrue("080442957X".equals(Isbn.normalise("0-8044-2957-x")));
    }

    @Test
    @DisplayName("normalise returns null only for genuinely unusable input")
    void normaliseRejectsUnusableInput() {
        org.junit.jupiter.api.Assertions.assertNull(Isbn.normalise(null));
        org.junit.jupiter.api.Assertions.assertNull(Isbn.normalise("abc"));
    }
}