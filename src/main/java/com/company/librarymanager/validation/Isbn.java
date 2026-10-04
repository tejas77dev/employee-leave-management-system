package com.company.librarymanager.validation;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ISBN-10 and ISBN-13 syntax and checksum, with no other dependencies.
 *
 * <p>Hyphens and spaces are stripped first, because a real scanned or typed
 * code carries them and they carry no meaning. What is left must be digits
 * only, and must satisfy the weighted-sum check the ISBN system defines.
 *
 * <p>The checksum is the point of doing this at all: a number of the right
 * length but with a mistyped digit is almost always a typo, and catching it at
 * entry is the only moment anyone can tell whether the intended book was the
 * one in hand. A library that stores wrong codes cannot find its own stock.
 */
public final class Isbn {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s-]");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern ISBN10_WITH_X = Pattern.compile("\\d{9}X");

    private Isbn() {
    }

/**
 * Hyphens stripped and spaces removed, so {@code "978-0-13-235088-4"}
 * becomes {@code "9780132350884"}.
 *
 * <p>Returns null for anything that is not a plausible ISBN once separators are
 * gone, which is what rejects a letter in a 13-digit code. An ISBN-10 may end in
 * the check digit {@code X}, so that one letter is allowed through; anything
 * else, and any {@code X} anywhere but the last position, is not.
 */
public static String normalise(String value) {
    if (value == null) {
        return null;
    }
    String stripped = SEPARATORS.matcher(value).replaceAll("").toUpperCase(Locale.ROOT);
    if (DIGITS.matcher(stripped).matches()) {
        return stripped;
    }
    // Nine digits followed by an X check digit, and nothing else.
    return ISBN10_WITH_X.matcher(stripped).matches() ? stripped : null;
}

/**
 * True when the value is a syntactically valid ISBN-10 or ISBN-13.
 *
 * <p>Normalisation returns null for anything unrecognised, so that null is
 * checked here: a form field left blank, or holding a letter where a digit
 * belongs, has to read as invalid rather than throw. Every caller is a form
 * validator, and a validator that crashes on empty input takes the whole page
 * down with it.
 */
public static boolean isValid(String value) {
    String digits = normalise(value);
    if (digits == null) {
        return false;
    }
    if (digits.length() == 10) {
        return isValidIsbn10(digits);
    }
    if (digits.length() == 13) {
        return isValidIsbn13(digits);
    }
    return false;
}

    /**
     * ISBN-10: weights 10 down to 1, with the last character allowed to be 'X'
     * for a check digit. The weighted sum must be a multiple of 11.
     */
    private static boolean isValidIsbn10(String digits) {
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char c = digits.charAt(i);
            int value;
            if (c == 'X' || c == 'x') {
                // 'X' is only legal as the final check digit.
                if (i != 9) {
                    return false;
                }
                value = 10;
            } else {
                value = c - '0';
            }
            sum += value * (10 - i);
        }
        return sum % 11 == 0;
    }

    /** ISBN-13: alternating weights of 1 and 3, summing to a multiple of 10. */
    private static boolean isValidIsbn13(String digits) {
        int sum = 0;
        for (int i = 0; i < 13; i++) {
            int value = digits.charAt(i) - '0';
            sum += value * (i % 2 == 0 ? 1 : 3);
        }
        return sum % 10 == 0;
    }

    /**
     * The message shown against the field, so the user learns the expected shape
     * rather than just being told it failed.
     */
    public static String describe() {
        return "Enter a valid ISBN-10 or ISBN-13";
    }
}
