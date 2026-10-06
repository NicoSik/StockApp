package stockapp.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rounding rules for every amount and percentage the API returns.
 *
 * <p>One definition so the paper portfolio and the holdings aggregator cannot
 * drift apart on something as visible as how a total is rounded.
 */
public final class Money {

    /** Øre and cents: two decimals for every amount. */
    public static final int SCALE = 2;
    /** Percentages are returned to two decimals, e.g. {@code 12.34}. */
    public static final int PERCENT_SCALE = 2;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Money() {
    }

    /** Rounds to two decimals, half up. Null becomes zero. */
    public static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** A market-data price (a double) as an amount. */
    public static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** {@code part / whole * 100}, or zero when the denominator is zero or missing. */
    public static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return BigDecimal.ZERO.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
        }
        return part.multiply(HUNDRED).divide(whole, PERCENT_SCALE, RoundingMode.HALF_UP);
    }
}
