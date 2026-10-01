package com.company.leavemanager.domain;

public enum AuditAction {
    REQUEST_SUBMITTED("Request submitted"),
    REQUEST_APPROVED("Request approved"),
    REQUEST_REJECTED("Request rejected"),
    EMPLOYEE_CREATED("Employee added"),
    EMPLOYEE_UPDATED("Employee updated"),
    LEAVE_TYPE_CREATED("Leave type created"),
    LEAVE_TYPE_UPDATED("Leave type updated"),
    ENTITLEMENT_UPDATED("Allowance changed"),
    PASSWORD_CHANGED("Password changed");

    private final String label;

    AuditAction(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
