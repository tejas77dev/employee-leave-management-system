package com.company.librarymanager.domain;

/**
 * The states a book request moves through.
 *
 * <p>Only three, and the third is not optional: a request row is never deleted,
 * so a member asking "did anyone look at my request?" is answered from this
 * rather than from an empty table.
 */
public enum RequestStatus {
    /** Raised by a member and not yet looked at by the desk. */
    PENDING,
    /** Granted by the desk. The copy was taken off the shelf at that moment. */
    APPROVED,
    /** Turned down, with the reason left on the row. */
    REJECTED
}