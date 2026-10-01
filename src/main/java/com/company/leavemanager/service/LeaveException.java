package com.company.leavemanager.service;

/**
 * A business rule the user can be shown the reason for.
 *
 * <p>Anything thrown that is <em>not</em> this type is treated as an
 * unexpected failure and reported without detail, so internal errors and
 * database errors never leak their text to a user.
 */
public class LeaveException extends RuntimeException {

    public LeaveException(String message) {
        super(message);
    }
}
