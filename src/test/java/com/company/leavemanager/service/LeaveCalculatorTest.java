package com.company.leavemanager.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The day-counting rules, which are the part of a leave app most likely to
 * disagree with what a person expects.
 */
class LeaveCalculatorTest {

    @Test
    void countsWeekdaysAndSkipsWeekends() {
        // Mon 6 Apr to Fri 10 Apr 2026 is five weekdays.
        assertThat(LeaveCalculator.calculateDays(LocalDate.of(2026, 4, 6), LocalDate.of(2026, 4, 10), false))
                .isEqualByComparingTo("5");
    }

    @Test
    void weekendOnlyRangeCostsNothing() {
        // Sat 11 Apr and Sun 12 Apr 2026.
        assertThat(LeaveCalculator.calculateDays(LocalDate.of(2026, 4, 11), LocalDate.of(2026, 4, 12), false))
                .isEqualByComparingTo("0");
    }

    @Test
    void spanAcrossWeekendCountsTheWeekdaysOnly() {
        // Fri 10 Apr to Mon 13 Apr 2026 spans a weekend, so only the Friday
        // and the Monday count.
        assertThat(LeaveCalculator.calculateDays(LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 13), false))
                .isEqualByComparingTo("2");
    }

    @Test
    void halfDayIsHalfADay() {
        assertThat(LeaveCalculator.calculateDays(LocalDate.of(2026, 4, 6), LocalDate.of(2026, 4, 6), true))
                .isEqualByComparingTo("0.5");
    }

    @Test
    void halfDaySpanningSeveralDatesIsRejected() {
        assertThatThrownBy(() -> LeaveCalculator.calculateDays(
                LocalDate.of(2026, 4, 6), LocalDate.of(2026, 4, 7), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void singleDayIsOneDay() {
        assertThat(LeaveCalculator.calculateDays(LocalDate.of(2026, 4, 6), LocalDate.of(2026, 4, 6), false))
                .isEqualByComparingTo("1");
    }

    @Test
    void rangesAreInclusiveAtBothEnds() {
        // A request meeting another on one shared day counts as overlapping.
        assertThat(LeaveCalculator.rangesOverlap(LocalDate.of(2026, 4, 6), LocalDate.of(2026, 4, 8),
                LocalDate.of(2026, 4, 8), LocalDate.of(2026, 4, 10))).isTrue();
    }

    @Test
    void adjacentRangesDoNotOverlap() {
        assertThat(LeaveCalculator.rangesOverlap(LocalDate.of(2026, 4, 6), LocalDate.of(2026, 4, 8),
                LocalDate.of(2026, 4, 9), LocalDate.of(2026, 4, 10))).isFalse();
    }

    @Test
    void remainingSubtractsUsedAndPending() {
        assertThat(LeaveCalculator.remaining(BigDecimal.valueOf(15), BigDecimal.valueOf(4), BigDecimal.valueOf(2)))
                .isEqualByComparingTo("9");
    }

    @Test
    void remainingCanGoNegativeSoOverdrawIsVisible() {
        assertThat(LeaveCalculator.remaining(BigDecimal.valueOf(3), BigDecimal.valueOf(4), BigDecimal.ZERO))
                .isEqualByComparingTo("-1");
    }

    @Test
    void formatsWholeDaysWithoutATrailingZero() {
        assertThat(LeaveCalculator.formatDays(BigDecimal.valueOf(5))).isEqualTo("5");
        assertThat(LeaveCalculator.formatDays(new BigDecimal("0.50"))).isEqualTo("0.5");
    }

    @Test
    void rejectsAnUnorderedRange() {
        assertThatThrownBy(() -> LeaveCalculator.calculateDays(
                LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 6), false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}