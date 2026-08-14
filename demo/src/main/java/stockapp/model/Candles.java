package stockapp.model;

import java.util.List;

/**
 * A chart series for one symbol over one range.
 *
 * @param baseline the price the range's change is measured against: the
 *                 previous session close for {@code 1D}, otherwise the close of
 *                 the bar immediately before the window. Null when unknown, in
 *                 which case the client falls back to the first point.
 * @param source   {@code "alpaca"} for live data, {@code "database"} when a
 *                 cached daily history was used because the API was unreachable.
 */
public record Candles(String symbol,
                      String range,
                      String timeframe,
                      List<Candle> points,
                      Double baseline,
                      String source) {
}
