package stockapp.model;

/**
 * One OHLCV bar.
 *
 * <p>{@code time} is epoch milliseconds UTC, which is what the browser chart
 * consumes directly. Prices are {@code double} because these values are only
 * ever drawn, never used for accounting - money in the portfolio is
 * {@link java.math.BigDecimal} throughout.
 */
public record Candle(long time, double open, double high, double low, double close, long volume) {
}
