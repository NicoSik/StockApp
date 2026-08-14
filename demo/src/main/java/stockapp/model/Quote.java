package stockapp.model;

/**
 * A point-in-time snapshot of one symbol.
 *
 * <p>{@code change} and {@code changePercent} are stored rather than derived so
 * that they cross the JSON boundary - Gson serialises record components, not
 * computed accessors. Use {@link #of} instead of the canonical constructor.
 *
 * @param price         last trade price
 * @param previousClose previous session's official close, the baseline every
 *                      "today" number is measured against
 * @param asOf          epoch millis of the last trade
 */
public record Quote(String symbol,
                    double price,
                    Double previousClose,
                    Double change,
                    Double changePercent,
                    Double open,
                    Double high,
                    Double low,
                    Double vwap,
                    long volume,
                    long asOf) {

    public static Quote of(String symbol,
                           double price,
                           Double previousClose,
                           Double open,
                           Double high,
                           Double low,
                           Double vwap,
                           long volume,
                           long asOf) {
        Double change = null;
        Double changePercent = null;
        if (previousClose != null && previousClose != 0.0) {
            change = price - previousClose;
            changePercent = change / previousClose * 100.0;
        }
        return new Quote(symbol, price, previousClose, change, changePercent,
                open, high, low, vwap, volume, asOf);
    }
}
