package stockapp.model;

/**
 * A watchlist sparkline: the latest session's closes, downsampled.
 *
 * <p>Deliberately not a {@link Candles} - a row 90 pixels wide needs a short
 * array of numbers, not full OHLCV bars, and a 30-row watchlist refreshing
 * every few seconds makes that difference matter.
 *
 * @param baseline previous session close, so the row can be coloured against
 *                 the same reference as the quote
 */
public record Spark(String symbol, double[] points, Double baseline) {
}
