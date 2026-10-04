package com.company.librarymanager.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fine arithmetic, with no database or web dependencies.
 *
 * <p>The rate and the cap are passed in rather than read from configuration, so
 * a rule can be checked directly without standing up a Spring context.
 *
 * <p>Money is {@link BigDecimal} throughout and is rounded half-up to paise at
 * the end of each calculation. Rounding per day would let a rate such as
 * 1.50/3 days compound differently from 1.50/2 days, and a total that depends
 * on which arithmetic route produced it is not a total anyone can check.
 */
public final class FineCalculator {

    private FineCalculator() {
    }

    /**
     * What a late return costs: one day's rate per day late, capped.
     *
     * <p>Returns zero for a loan that is not late. The cap applies to the whole
     * fine rather than to the rate, so however late a book is, what is owed
     * never exceeds the ceiling the library has published.
     */
    public static BigDecimal lateFine(long daysOverdue, BigDecimal dailyRate, BigDecimal cap) {
        if (daysOverdue <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (dailyRate == null || dailyRate.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal raw = dailyRate.multiply(BigDecimal.valueOf(daysOverdue));
        if (cap != null && cap.signum() > 0 && raw.compareTo(cap) > 0) {
            return cap.setScale(2, RoundingMode.HALF_UP);
        }
        return raw.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * What an unreturned copy costs.
     *
     * <p>A flat replacement charge rather than a per-day rate: the book is gone,
     * so there is no due date left to run from, and a member should not be
     * billed an open-ended daily sum for a copy they have already reported
     * missing.
     */
    public static BigDecimal lostFine(BigDecimal replacementCharge) {
        if (replacementCharge == null || replacementCharge.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return replacementCharge.setScale(2, RoundingMode.HALF_UP);
    }

    /** "12.50", or an empty string for null, for display and form fields. */
    public static String formatMoney(BigDecimal value) {
        return value == null ? "" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Parses a money field. Rejects anything that is not a plain decimal, so a
     * blank or a stray letter cannot be read as zero.
     */
    public static BigDecimal parseMoney(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Parses a non-negative integer, or null when the text is not one. */
    public static Integer parsePositiveInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
