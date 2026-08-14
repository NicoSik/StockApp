package stockapp.model;

import java.time.Duration;
import java.time.Period;
import java.util.Locale;

/**
 * The chart ranges offered in the UI, and how each one maps onto an Alpaca bar
 * request.
 *
 * <p>The bar size for each range is chosen to land near 80-260 points: dense
 * enough to show real structure, sparse enough that the line stays readable and
 * the payload stays small.
 *
 * <p>{@code lookback} is deliberately generous relative to the label (10 days
 * for "1W", 8 days for "1D") because markets close for weekends and holidays.
 * Requesting a wider window and then trimming is what makes the chart correct
 * on a Monday morning or the day after Thanksgiving.
 */
public enum Range {

    /** The most recent trading session only, at 5-minute resolution. */
    DAY("1D", "5Min", Period.ofDays(8), true),
    WEEK("1W", "30Min", Period.ofDays(10), false),
    MONTH("1M", "1Hour", Period.ofDays(32), false),
    QUARTER("3M", "1Day", Period.ofDays(95), false),
    YEAR("1Y", "1Day", Period.ofDays(370), false),
    FIVE_YEAR("5Y", "1Week", Period.ofYears(5).plusDays(10), false);

    private final String label;
    private final String timeframe;
    private final Period lookback;
    private final boolean singleSession;

    Range(String label, String timeframe, Period lookback, boolean singleSession) {
        this.label = label;
        this.timeframe = timeframe;
        this.lookback = lookback;
        this.singleSession = singleSession;
    }

    /** The value used on the wire and in the UI, e.g. {@code "1D"}. */
    public String label() {
        return label;
    }

    /** The Alpaca {@code timeframe} query parameter, e.g. {@code "5Min"}. */
    public String timeframe() {
        return timeframe;
    }

    public Period lookback() {
        return lookback;
    }

    /**
     * True when only the latest session's bars should be kept. Everything older
     * in the response is discarded after the fetch.
     */
    public boolean singleSession() {
        return singleSession;
    }

    /** Bars at or above daily resolution are what gets persisted to the database. */
    public boolean isDaily() {
        return timeframe.equals("1Day") || timeframe.equals("1Week");
    }

    /** How long a cached response for this range stays fresh. */
    public Duration cacheTtl() {
        // Intraday ranges move constantly; a 5-year weekly chart does not.
        return switch (this) {
            case DAY -> Duration.ofSeconds(30);
            case WEEK, MONTH -> Duration.ofMinutes(5);
            case QUARTER, YEAR -> Duration.ofMinutes(30);
            case FIVE_YEAR -> Duration.ofHours(6);
        };
    }

    /** Parses a UI label; falls back to {@link #DAY} for anything unrecognised. */
    public static Range parse(String value) {
        if (value != null) {
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            for (Range range : values()) {
                if (range.label.equals(normalized)) {
                    return range;
                }
            }
        }
        return DAY;
    }
}
