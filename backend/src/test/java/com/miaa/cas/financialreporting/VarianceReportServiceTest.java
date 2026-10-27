package com.miaa.cas.financialreporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The sign and assessment rules of the variance analysis, including credit-balance (revenue) lines. */
class VarianceReportServiceTest {

    private static final BigDecimal TEN = BigDecimal.TEN;

    @Test
    void spendingBelowBudgetIsFavourable() {
        assertThat(VarianceReportService.values(bd("1200.00"), bd("600.00"), bd("450.00"), false, true, TEN))
                .containsExactly(bd("1200.00"), bd("600.00"), bd("450.00"), bd("150.00"), bd("25.0"), bd("750.00"), "Favourable");
    }

    @Test
    void revenueAboveBudgetIsFavourableAndBelowIsUnfavourable() {
        assertThat(VarianceReportService.values(bd("1200.00"), bd("600.00"), bd("700.00"), true, true, TEN))
                .containsExactly(bd("1200.00"), bd("600.00"), bd("700.00"), bd("100.00"), bd("16.7"), bd("500.00"), "Favourable");
        assertThat(VarianceReportService.values(bd("1200.00"), bd("600.00"), bd("480.00"), true, true, TEN))
                .last().isEqualTo("Unfavourable");
    }

    @Test
    void edgeCases() {
        // Exactly at the threshold counts as a variance; nothing budgeted to date gives no percentage.
        assertThat(VarianceReportService.values(bd("1200.00"), bd("100.00"), bd("110.00"), false, true, TEN).get(6)).isEqualTo("Unfavourable");
        assertThat(VarianceReportService.values(bd("0.00"), bd("0.00"), bd("0.00"), false, true, TEN))
                .containsExactly(bd("0.00"), bd("0.00"), bd("0.00"), bd("0.00"), null, bd("0.00"), "No activity");
        assertThat(VarianceReportService.values(bd("0.00"), bd("0.00"), bd("50.00"), false, false, TEN).get(6)).isEqualTo("No allocation");
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }
}
