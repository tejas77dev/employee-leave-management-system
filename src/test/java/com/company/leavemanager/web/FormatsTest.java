package com.company.leavemanager.web;

import com.company.leavemanager.domain.AuditAction;
import com.company.leavemanager.domain.LeaveRequest;
import com.company.leavemanager.domain.RequestStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Display helpers. These matter because a wrong-looking figure on screen is the
 * first thing a user reports, and a wrong-looking audit timestamp is the first
 * thing HR disputes.
 */
class FormatsTest {

    private final Formats formats = new Formats();

    @Test
    void wholeDaysHaveNoDecimalPoint() {
        assertThat(formats.days(BigDecimal.valueOf(5))).isEqualTo("5");
        assertThat(formats.days(BigDecimal.ZERO)).isEqualTo("0");
    }

    @Test
    void halfDaysShowAsAHalf() {
        assertThat(formats.days(new BigDecimal("0.5"))).isEqualTo("0.5");
    }

    @Test
    void identicalRangeCollapsesToOneDate() {
        LocalDate day = LocalDate.of(2026, 4, 6);
        assertThat(formats.dateRange(day, day)).isEqualTo(formats.date(day));
    }

    @Test
    void differentEndsShowBothDates() {
        assertThat(formats.dateRange(LocalDate.of(2026, 4, 6), LocalDate.of(2026, 4, 10)))
                .isEqualTo("6 Apr 2026 to 10 Apr 2026");
    }

    @Test
    void missingDateRendersAsEmptyRatherThanNull() {
        assertThat(formats.date(null)).isEmpty();
        assertThat(formats.dateRange(null, LocalDate.of(2026, 4, 10))).isEmpty();
    }

    @Test
    void shortIdKeepsTheTailSoItMatchesALogLine() {
        assertThat(formats.shortId("abcdef123456")).isEqualTo("123456");
        assertThat(formats.shortId("abc")).isEqualTo("abc");
        assertThat(formats.shortId(null)).isEmpty();
    }

    @Test
    void percentIsClampedToZeroAndOneHundred() {
        assertThat(formats.percent(BigDecimal.valueOf(10), BigDecimal.ZERO, BigDecimal.ZERO)).isZero();
        assertThat(formats.percent(BigDecimal.valueOf(10), BigDecimal.valueOf(99), BigDecimal.ZERO))
                .isEqualTo(100);
        // A zero entitlement must not divide by zero.
        assertThat(formats.percent(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO)).isZero();
    }

    @Test
    void percentCountsPendingAgainstTheEntitlement() {
        assertThat(formats.percent(BigDecimal.valueOf(10), BigDecimal.valueOf(4), BigDecimal.valueOf(1)))
                .isEqualTo(50);
    }

    @Test
    void statusBadgeClassIsLowercase() {
        assertThat(formats.statusClass(RequestStatus.PENDING)).isEqualTo("badge badge-pending");
        assertThat(formats.statusClass(RequestStatus.APPROVED)).isEqualTo("badge badge-approved");
        assertThat(formats.statusClass(RequestStatus.REJECTED)).isEqualTo("badge badge-rejected");
    }

    @Test
    void auditMetadataBecomesReadableChips() {
        assertThat(formats.metadataChips("{\"days\":2,\"employee\":\"Sam Patel\"}"))
                .containsExactlyInAnyOrder("days = 2", "employee = Sam Patel");
    }

    @Test
    void unparseableMetadataIsShownRawRatherThanHidden() {
        assertThat(formats.metadataChips("not json")).containsExactly("not json");
    }

    @Test
    void emptyMetadataProducesNoChips() {
        assertThat(formats.metadataChips(null)).isEmpty();
        assertThat(formats.metadataChips("   ")).isEmpty();
    }

    @Test
    void timestampOfMissingValueIsEmpty() {
        assertThat(formats.timestamp(null)).isEmpty();
    }

    @Test
    void timestampFormatsAnAuditInstant() {
        assertThat(formats.timestamp(Instant.parse("2026-04-06T09:30:00Z"))).contains("2026");
    }

    @Test
    void actionLabelsAreNeverBlank() {
        for (AuditAction action : AuditAction.values()) {
            assertThat(action.label()).isNotBlank();
        }
    }
}