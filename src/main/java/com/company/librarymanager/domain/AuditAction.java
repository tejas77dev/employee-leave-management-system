package com.company.librarymanager.domain;

public enum AuditAction {
    BOOK_CREATED("Book added"),
    BOOK_UPDATED("Book updated"),
    BOOK_DELETED("Book removed"),
    BOOK_ISSUED("Book issued"),
    BOOK_RETURNED("Book returned"),
    BOOK_MARKED_LOST("Book marked lost"),
    BOOK_REQUESTED("Book requested"),
    BOOK_REQUEST_APPROVED("Request approved"),
    BOOK_REQUEST_REJECTED("Request rejected"),
    FINE_PAID("Fine settled"),
    MEMBER_CREATED("Member added"),
    MEMBER_UPDATED("Member updated"),
    CATEGORY_CREATED("Category added"),
    CATEGORY_UPDATED("Category updated"),
    USER_CREATED("User added"),
    USER_UPDATED("User updated"),
    PASSWORD_CHANGED("Password changed");

    private final String label;

    AuditAction(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
