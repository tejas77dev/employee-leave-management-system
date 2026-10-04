package com.company.librarymanager.service;

/**
 * A business rule the user can be shown the reason for.
 *
 * <p>Anything thrown that is <em>not</em> this type is treated as an
 * unexpected failure and reported without detail, so internal errors and
 * database errors never leak their text to a user.
 */
public class LibraryException extends RuntimeException {

    public LibraryException(String message) {
        super(message);
    }
}
