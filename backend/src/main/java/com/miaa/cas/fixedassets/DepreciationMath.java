package com.miaa.cas.fixedassets;

import com.miaa.cas.common.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Straight-line depreciation, monthly. The monthly charge is (cost - residual) / life rounded half-even to
 * centavos; the final month absorbs rounding so the total equals the depreciable amount exactly. Whether
 * depreciation starts in the month the asset is placed in service or the month after is MIAA policy (C-30).
 */
public final class DepreciationMath {

    public enum Start { SAME_MONTH, NEXT_MONTH }

    private DepreciationMath() {
    }

    public static BigDecimal monthly(BigDecimal cost, BigDecimal residual, int lifeMonths) {
        return cost.subtract(residual).divide(BigDecimal.valueOf(lifeMonths), 2, RoundingMode.HALF_EVEN);
    }

    /** Accumulated depreciation that should exist at the end of {@code month}. */
    public static BigDecimal expectedAccumulated(BigDecimal cost, BigDecimal residual, int lifeMonths, LocalDate inService,
            YearMonth month, Start start) {
        YearMonth first = YearMonth.from(inService).plusMonths(start == Start.NEXT_MONTH ? 1 : 0);
        if (month.isBefore(first)) {
            return Money.ZERO;
        }
        long n = first.until(month, ChronoUnit.MONTHS) + 1;
        BigDecimal base = Money.of(cost.subtract(residual));
        if (n >= lifeMonths) {
            return base;
        }
        return Money.of(monthly(cost, residual, lifeMonths).multiply(BigDecimal.valueOf(n)).min(base));
    }

    /**
     * Accumulated depreciation of a migrated asset at the end of {@code month}: the legacy accumulated amount at
     * cutover, plus straight-line depreciation of what remains over the remaining life, starting in the cutover
     * month. The final month absorbs rounding.
     */
    public static BigDecimal expectedFromOpening(BigDecimal cost, BigDecimal residual, BigDecimal openingAccumulated,
            int remainingMonths, YearMonth basisMonth, YearMonth month) {
        BigDecimal base = Money.of(cost.subtract(residual));
        BigDecimal opening = Money.of(openingAccumulated);
        if (month.isBefore(basisMonth)) {
            return opening;
        }
        long n = basisMonth.until(month, ChronoUnit.MONTHS) + 1;
        if (remainingMonths <= 0 || n >= remainingMonths) {
            return base;
        }
        BigDecimal monthly = base.subtract(opening).divide(BigDecimal.valueOf(remainingMonths), 2, RoundingMode.HALF_EVEN);
        return Money.of(opening.add(monthly.multiply(BigDecimal.valueOf(n))).min(base));
    }

    /**
     * Accumulated depreciation at the end of {@code month} of an asset remeasured by an impairment or revaluation: the
     * accumulated depreciation at the remeasurement, plus the new depreciable amount spread straight-line over the
     * remaining life from the remeasurement month. Before that month the basis amount stands (earlier months were
     * settled before posting). The final month absorbs rounding.
     */
    public static BigDecimal expectedFromBasis(BigDecimal basisAccumulated, BigDecimal depreciable, int remainingMonths,
            YearMonth basisMonth, YearMonth month) {
        BigDecimal opening = Money.of(basisAccumulated);
        BigDecimal end = Money.of(opening.add(depreciable));
        if (month.isBefore(basisMonth)) {
            return opening;
        }
        long n = basisMonth.until(month, ChronoUnit.MONTHS) + 1;
        if (remainingMonths <= 0 || n >= remainingMonths) {
            return end;
        }
        return Money.of(opening.add(monthlyFromBasis(depreciable, remainingMonths).multiply(BigDecimal.valueOf(n))).min(end));
    }

    /** Monthly charge after a remeasurement. */
    public static BigDecimal monthlyFromBasis(BigDecimal depreciable, int remainingMonths) {
        if (remainingMonths <= 0) {
            return Money.ZERO;
        }
        return depreciable.divide(BigDecimal.valueOf(remainingMonths), 2, RoundingMode.HALF_EVEN);
    }

    /**
     * The last month of an asset's depreciation schedule: from the latest remeasurement when there is one, else from
     * the migration basis, else from the in-service date.
     */
    public static YearMonth lastMonth(LocalDate inService, int lifeMonths, Start start, LocalDate migrationBasis,
            Integer migrationRemaining, LocalDate remeasuredBasis, Integer remeasuredRemaining) {
        if (remeasuredBasis != null) {
            return YearMonth.from(remeasuredBasis).plusMonths(remeasuredRemaining - 1L);
        }
        if (migrationBasis != null) {
            return YearMonth.from(migrationBasis).plusMonths(migrationRemaining - 1L);
        }
        return YearMonth.from(inService).plusMonths((start == Start.NEXT_MONTH ? 1 : 0) + lifeMonths - 1L);
    }

    /** Monthly charge of a migrated asset over its remaining life. */
    public static BigDecimal monthlyFromOpening(BigDecimal cost, BigDecimal residual, BigDecimal openingAccumulated, int remainingMonths) {
        if (remainingMonths <= 0) {
            return Money.ZERO;
        }
        return cost.subtract(residual).subtract(openingAccumulated).divide(BigDecimal.valueOf(remainingMonths), 2, RoundingMode.HALF_EVEN);
    }
}
