package com.miaa.cas.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Monetary conventions: PHP amounts are {@code BigDecimal} with scale 2. Inputs with more than two
 * decimal places are rejected by validation rather than silently rounded; HALF_EVEN applies only where
 * the system itself derives an amount.
 */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);

    private Money() {
    }

    public static BigDecimal of(BigDecimal value) {
        return value == null ? ZERO : value.setScale(SCALE, ROUNDING);
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    public static boolean isZero(BigDecimal value) {
        return value == null || value.signum() == 0;
    }
}
