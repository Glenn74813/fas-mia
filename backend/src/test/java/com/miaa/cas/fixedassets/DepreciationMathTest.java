package com.miaa.cas.fixedassets;

import static org.assertj.core.api.Assertions.assertThat;

import com.miaa.cas.fixedassets.DepreciationMath.Start;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class DepreciationMathTest {

    private static final BigDecimal COST = new BigDecimal("10000.00");

    @Test
    void finalMonthAbsorbsRounding() {
        // 10,000 / 3 = 3,333.33 a month; the third month brings the total to exactly 10,000.
        assertThat(DepreciationMath.monthly(COST, BigDecimal.ZERO, 3)).isEqualByComparingTo("3333.33");
        LocalDate inService = LocalDate.of(2026, 1, 15);
        assertThat(exp(inService, 2026, 2, 3, Start.NEXT_MONTH)).isEqualByComparingTo("3333.33");
        assertThat(exp(inService, 2026, 3, 3, Start.NEXT_MONTH)).isEqualByComparingTo("6666.66");
        assertThat(exp(inService, 2026, 4, 3, Start.NEXT_MONTH)).isEqualByComparingTo("10000.00");
        assertThat(exp(inService, 2027, 1, 3, Start.NEXT_MONTH)).isEqualByComparingTo("10000.00");
    }

    @Test
    void startConventionAndResidualValue() {
        LocalDate inService = LocalDate.of(2026, 1, 31);
        assertThat(exp(inService, 2026, 1, 12, Start.NEXT_MONTH)).isEqualByComparingTo("0.00");
        assertThat(exp(inService, 2026, 1, 12, Start.SAME_MONTH)).isEqualByComparingTo("833.33");
        assertThat(exp(inService, 2025, 12, 12, Start.SAME_MONTH)).isEqualByComparingTo("0.00");
        BigDecimal withResidual = DepreciationMath.expectedAccumulated(COST, new BigDecimal("1000.00"), 12, inService,
                YearMonth.of(2027, 6), Start.NEXT_MONTH);
        assertThat(withResidual).isEqualByComparingTo("9000.00");
    }

    @Test
    void migratedAssetsContinueFromTheirOpeningBasis() {
        // Cost 10,000, legacy accumulated 4,000 at a January cutover, 3 months left: 2,000 a month from January.
        BigDecimal open = new BigDecimal("4000.00");
        YearMonth cutover = YearMonth.of(2027, 1);
        assertThat(DepreciationMath.monthlyFromOpening(COST, BigDecimal.ZERO, open, 3)).isEqualByComparingTo("2000.00");
        assertThat(DepreciationMath.expectedFromOpening(COST, BigDecimal.ZERO, open, 3, cutover, YearMonth.of(2026, 12)))
                .isEqualByComparingTo("4000.00");
        assertThat(DepreciationMath.expectedFromOpening(COST, BigDecimal.ZERO, open, 3, cutover, cutover)).isEqualByComparingTo("6000.00");
        assertThat(DepreciationMath.expectedFromOpening(COST, BigDecimal.ZERO, open, 3, cutover, YearMonth.of(2027, 3)))
                .isEqualByComparingTo("10000.00");
        // 6,000 over 7 months is 857.14; the last month absorbs the rounding.
        assertThat(DepreciationMath.expectedFromOpening(COST, BigDecimal.ZERO, open, 7, cutover, YearMonth.of(2027, 6)))
                .isEqualByComparingTo("9142.84");
        assertThat(DepreciationMath.expectedFromOpening(COST, BigDecimal.ZERO, open, 7, cutover, YearMonth.of(2027, 7)))
                .isEqualByComparingTo("10000.00");
        // Fully depreciated at cutover: nothing more is charged.
        assertThat(DepreciationMath.expectedFromOpening(COST, new BigDecimal("1000.00"), new BigDecimal("9000.00"), 0, cutover, cutover))
                .isEqualByComparingTo("9000.00");
    }

    private static BigDecimal exp(LocalDate inService, int y, int m, int life, Start start) {
        return DepreciationMath.expectedAccumulated(COST, BigDecimal.ZERO, life, inService, YearMonth.of(y, m), start);
    }
}
