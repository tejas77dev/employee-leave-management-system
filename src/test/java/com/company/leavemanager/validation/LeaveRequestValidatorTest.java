package com.company.leavemanager.validation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Form-level validation, kept separate from the database-backed service rules. */
class LeaveRequestValidatorTest {

    @Test
    void rejectsMissingLeaveType() {
        Map<String, String> errors = LeaveRequestValidator.of()
                .validate("", "2026-04-06", "2026-04-06", false, "", "");
        assertThat(errors).containsKey("leaveTypeId");
    }

    @Test
    void rejectsUnparseableDates() {
        Map<String, String> errors = LeaveRequestValidator.of()
                .validate("type-1", "not-a-date", "2026-04-06", false, "", "");
        assertThat(errors).containsKey("startDate");
    }

    @Test
    void rejectsEndBeforeStart() {
        Map<String, String> errors = LeaveRequestValidator.of()
                .validate("type-1", "2026-04-10", "2026-04-06", false, "", "");
        assertThat(errors).containsKey("endDate");
    }

    @Test
    void halfDayRequiresAPartOfDay() {
        Map<String, String> errors = LeaveRequestValidator.of()
                .validate("type-1", "2026-04-06", "2026-04-06", true, "", "");
        assertThat(errors).containsKey("partOfDay");
    }

    @Test
    void acceptsAFullDayRequest() {
        Map<String, String> errors = LeaveRequestValidator.of()
                .validate("type-1", "2026-04-06", "2026-04-07", false, "", "Holiday");
        assertThat(errors).isEmpty();
    }

    @Test
    void rejectsAnOverlongReason() {
        Map<String, String> errors = LeaveRequestValidator.of()
                .validate("type-1", "2026-04-06", "2026-04-06", false, "", "x".repeat(501));
        assertThat(errors).containsKey("reason");
    }
}