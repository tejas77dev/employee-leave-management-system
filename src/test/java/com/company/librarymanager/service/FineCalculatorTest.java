package com.company.librarymanager.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The fine rules, checked without a database.
 *
 * <p>The library's published terms are 1.00 a day and a ceiling of 50.00, with a
 * flat 25.00 for a copy written off. Those are the numbers used here so a change
 * to the arithmetic has to be made deliberately rather than drifting.
 */
class FineCalculatorTest {

    private static final BigDecimal DAILY = new BigDecimal("1.00");
    private static final BigDecimal CAP = new BigDecimal("50.00");
    private static final BigDecimal REPLACEMENT = new BigDecimal("25.00");

    @ParameterizedTest(name = "{0} day(s) late costs {1}")
    @CsvSource({
            "0, 0.00",
            "1, 1.00",
            "4, 4.00",
            "16, 16.00",
            "49, 49.00",
            "50, 50.00"
    })
    @DisplayName("charges the daily rate up to the cap")
    void chargesDailyRateUpToTheCap(long daysLate, String expected) {
        assertEquals(new BigDecimal(expected), FineCalculator.lateFine(daysLate, DAILY, CAP));
    }

    @ParameterizedTest(name = "{0} days late is still capped at 50.00")
    @ValueSource(longs = {51, 100, 3650})
    @DisplayName("never exceeds the published ceiling however late it is")
    void capsTheFine(long daysLate) {
        assertEquals(CAP, FineCalculator.lateFine(daysLate, DAILY, CAP));
    }

    @ParameterizedTest(name = "{0} days late when not late yet")
    @ValueSource(longs = {0, -1, -30})
    @DisplayName("charges nothing for a book that is not late")
    void chargesNothingWhenNotLate(long daysLate) {
        assertEquals(new BigDecimal("0.00"), FineCalculator.lateFine(daysLate, DAILY, CAP));
    }

    @Test
    @DisplayName("charges nothing when the library charges no daily rate")
    void chargesNothingWithoutARate() {
        assertEquals(new BigDecimal("0.00"), FineCalculator.lateFine(10, BigDecimal.ZERO, CAP));
        assertEquals(new BigDecimal("0.00"), FineCalculator.lateFine(10, null, CAP));
    }

    @Test
    @DisplayName("rounds once at the end, so the total is route-independent")
    void roundsHalfUpToPaise() {
        // 1.50/day for 3 days is 4.50; rounding per day would give the same here,
        // but 0.335/day for 3 is the case that distinguishes the two.
        assertEquals(new BigDecimal("1.01"),
                FineCalculator.lateFine(3, new BigDecimal("0.335"), null));
    }

    @Test
    @DisplayName("charges a flat replacement for a lost copy")
    void chargesFlatRateForLostCopy() {
        assertEquals(REPLACEMENT, FineCalculator.lostFine(REPLACEMENT));
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("charges nothing for a lost copy when no charge is configured")
    void chargesNothingWithoutAReplacementCharge(BigDecimal charge) {
        assertEquals(new BigDecimal("0.00"), FineCalculator.lostFine(charge));
    }

    @Test
    @DisplayName("parses a money field and refuses anything else")
    void parsesMoneyStrictly() {
        assertEquals(new BigDecimal("12.50"), FineCalculator.parseMoney("12.5"));
        assertEquals(new BigDecimal("12.50"), FineCalculator.parseMoney(" 12.50 "));
        org.junit.jupiter.api.Assertions.assertNull(FineCalculator.parseMoney("twelve"));
        org.junit.jupiter.api.Assertions.assertNull(FineCalculator.parseMoney(""));
        org.junit.jupiter.api.Assertions.assertNull(FineCalculator.parseMoney(null));
    }

    @Test
    @DisplayName("reads a blank count as absent, not as zero")
    void parsesCountsStrictly() {
        assertEquals(3, FineCalculator.parsePositiveInt("3"));
        assertEquals(0, FineCalculator.parsePositiveInt("0"));
        org.junit.jupiter.api.Assertions.assertNull(FineCalculator.parsePositiveInt("-1"));
        org.junit.jupiter.api.Assertions.assertNull(FineCalculator.parsePositiveInt("many"));
        org.junit.jupiter.api.Assertions.assertNull(FineCalculator.parsePositiveInt(null));
    }

    @Test
    @DisplayName("formats money to two decimal places for display")
    void formatsMoneyForDisplay() {
        assertEquals("12.50", FineCalculator.formatMoney(new BigDecimal("12.5")));
        assertEquals("0.00", FineCalculator.formatMoney(BigDecimal.ZERO));
        assertEquals("", FineCalculator.formatMoney(null));
    }
}