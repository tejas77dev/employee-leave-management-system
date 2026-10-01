package com.company.leavemanager.domain;

public enum RequestStatus {
    PENDING,
    APPROVED,
    REJECTED;

    /** Only a pending request occupies days; a rejection frees them again. */
    public boolean blocksDates() {
        return this == PENDING || this == APPROVED;
    }
}
