package stockapp.service;

import stockapp.Config;
import stockapp.alpaca.AlpacaClient;
import stockapp.alpaca.AlpacaException;
import stockapp.model.Candle;
import stockapp.model.Candles;
import stockapp.model.MarketClock;
import stockapp.model.Quote;
import stockapp.model.Range;
import stockapp.model.Spark;
import stockapp.model.Stock;
import stockapp.repo.StockRepo;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the UI needs about live prices: quotes, chart series and the
 * market clock, each cached briefly so that a page full of tickers costs one
 * upstream call rather than one per widget.
 *
 * <p>When Alpaca cannot be reached the service degrades instead of failing:
 * quotes fall back to the last cached value, and charts fall back to the daily
 * bars stored in the database.
 */
public final class MarketData {

    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    private static final LocalTime REGULAR_OPEN = LocalTime.of(9, 30);
    private static final LocalTime REGULAR_CLOSE = LocalTime.of(16, 0);

    private final AlpacaClient alpaca;
    private final StockRepo stocks;

    private final Cache<String, Quote> quoteCache = new Cache<>();
    private final Cache<String, Candles> candleCache = new Cache<>();
    private final Cache<String, MarketClock> clockCache = new Cache<>();

    public MarketData(AlpacaClient alpaca, StockRepo stocks) {
        this.alpaca = alpaca;
        this.stocks = stocks;
    }

    // ------------------------------------------------------------------ clock

    public MarketClock clock() {
        MarketClock cached = clockCache.get("clock", Duration.ofSeconds(30), key -> {
            try {
                return alpaca.clock();
            } catch (AlpacaException e) {
                System.out.println("[market] clock unavailable: " + e.getMessage());
                return null;
            }
        });
        if (cached != null) {
            return cached;
        }
        MarketClock stale = clockCache.peekStale("clock");
        return stale != null ? stale : new MarketClock(false, "UNKNOWN", null, null, System.currentTimeMillis());
    }

    // ----------------------------------------------------------------- quotes

    /**
     * Quotes for many symbols in one upstream call.
     *
     * <p>Symbols already cached are served from memory and only the remainder
     * is requested, so refreshing a 30-row watchlist every few seconds costs
     * almost nothing.
     */
    public Map<String, Quote> quotes(Collection<String> symbols) {
        Map<String, Quote> result = new LinkedHashMap<>();
        List<String> misses = new ArrayList<>();

        for (String symbol : symbols) {
            String key = symbol.toUpperCase();
            Quote fresh = quoteCache.peek(key);
            if (fresh != null) {
                result.put(key, fresh);
            } else {
                misses.add(key);
            }
        }

        if (!misses.isEmpty()) {
            try {
                Map<String, Quote> fetched = alpaca.snapshots(misses);
                Duration ttl = Duration.ofSeconds(Config.QUOTE_CACHE_SECONDS);
                fetched.forEach((symbol, quote) -> {
                    quoteCache.put(symbol, quote, ttl);
                    result.put(symbol, quote);
                });
            } catch (AlpacaException e) {
                System.out.println("[market] quote fetch failed: " + e.getMessage());
            }
            // Whatever is still missing gets its last known value rather than
            // disappearing from the UI mid-session.
            for (String symbol : misses) {
                if (!result.containsKey(symbol)) {
                    Quote stale = quoteCache.peekStale(symbol);
                    if (stale != null) {
                        result.put(symbol, stale);
                    }
                }
            }
        }
        return result;
    }

    public Quote quote(String symbol) {
        return quotes(List.of(symbol)).get(symbol.toUpperCase());
    }

    // ---------------------------------------------------------------- candles

    /** A chart series for one symbol and range, cached for the range's TTL. */
    public Candles candles(Stock stock, Range range) {
        String key = stock.symbol().toUpperCase() + "|" + range.label();
        Candles cached = candleCache.get(key, range.cacheTtl(), k -> loadCandles(stock, range));
        if (cached != null) {
            return cached;
        }
        Candles stale = candleCache.peekStale(key);
        return stale != null ? stale
                : new Candles(stock.symbol(), range.label(), range.timeframe(), List.of(), null, "unavailable");
    }

    private Candles loadCandles(Stock stock, Range range) {
        Instant end = Instant.now();
        // Period must be subtracted from a date, not converted to a Duration:
        // months and years have no fixed length in days.
        Instant start = LocalDate.now(ZoneOffset.UTC)
                .minus(range.lookback())
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();

        try {
            List<Candle> bars = alpaca.bars(stock.symbol(), range.timeframe(), start, end, range.isDaily());
            if (bars.isEmpty()) {
                return fromDatabase(stock, range);
            }

            Double baseline;
            if (range.singleSession()) {
                LocalDate session = latestRegularSession(bars);
                baseline = previousSessionClose(bars, session);
                // Everything from that session onward, not just that calendar
                // day: during pre-market the newest bars belong to tomorrow's
                // date, and they are the continuation of this same line. Keying
                // on the day alone would collapse the chart to two points every
                // morning between 04:00 and the open.
                final LocalDate from = session;
                bars = bars.stream().filter(bar -> !sessionOf(bar).isBefore(from)).toList();
                if (baseline == null) {
                    Quote quote = quote(stock.symbol());
                    baseline = quote == null ? null : quote.previousClose();
                }
            } else {
                baseline = bars.get(0).close();
            }

            // Daily and weekly history is worth keeping: it powers the portfolio
            // value chart and is the offline fallback for this same endpoint.
            if (range.timeframe().equals("1Day")) {
                try {
                    stocks.saveDailyBars(stock.id(), bars);
                } catch (RuntimeException e) {
                    System.out.println("[market] could not persist bars for " + stock.symbol() + ": " + e.getMessage());
                }
            }

            return new Candles(stock.symbol(), range.label(), range.timeframe(), bars, baseline, "alpaca");
        } catch (AlpacaException e) {
            System.out.println("[market] bars failed for " + stock.symbol() + " " + range.label()
                    + ": " + e.getMessage());
            return fromDatabase(stock, range);
        }
    }

    /** Offline fallback: whatever daily history has already been stored. */
    private Candles fromDatabase(Stock stock, Range range) {
        LocalDate from = LocalDate.now().minus(range.lookback());
        List<Candle> stored = stocks.dailyBars(stock.id(), from);
        Double baseline = stored.isEmpty() ? null : stored.get(0).close();
        return new Candles(stock.symbol(), range.label(), "1Day", stored, baseline,
                stored.isEmpty() ? "unavailable" : "database");
    }

    /**
     * The most recent date that saw regular-hours trading.
     *
     * <p>This anchors the 1D window. Using the newest bar's date instead would
     * follow the calendar into a pre-market session that has barely started;
     * using the last day the market was genuinely open is what the "1D" label
     * means to a person looking at it before the bell.
     */
    private static LocalDate latestRegularSession(List<Candle> bars) {
        for (int i = bars.size() - 1; i >= 0; i--) {
            if (isRegularHours(bars.get(i))) {
                return sessionOf(bars.get(i));
            }
        }
        // Only extended-hours data available (a very thinly traded symbol).
        return sessionOf(bars.get(bars.size() - 1));
    }

    /**
     * Close of the last regular-hours bar of the session before {@code session}.
     *
     * <p>Derived from the same bar set the chart is drawn from, so the "today"
     * change the UI reports always matches the line it draws. Extended-hours
     * bars are excluded because the baseline should be the official close.
     */
    private static Double previousSessionClose(List<Candle> bars, LocalDate session) {
        Double lastRegularClose = null;
        for (Candle bar : bars) {
            LocalDate barSession = sessionOf(bar);
            if (!barSession.isBefore(session)) {
                break;
            }
            if (isRegularHours(bar)) {
                lastRegularClose = bar.close();
            }
        }
        return lastRegularClose;
    }

    private static LocalDate sessionOf(Candle bar) {
        return Instant.ofEpochMilli(bar.time()).atZone(MARKET_ZONE).toLocalDate();
    }

    private static boolean isRegularHours(Candle bar) {
        LocalTime time = Instant.ofEpochMilli(bar.time()).atZone(MARKET_ZONE).toLocalTime();
        return !time.isBefore(REGULAR_OPEN) && time.isBefore(REGULAR_CLOSE);
    }

    // -------------------------------------------------------------- sparklines

    /** Target point count for a sparkline: enough to show shape, cheap to send. */
    private static final int SPARK_POINTS = 48;

    private final Cache<String, Spark> sparkCache = new Cache<>();

    /**
     * Latest-session sparklines for a whole watchlist in one upstream request.
     *
     * <p>Symbols with a fresh cached spark are excluded from the fetch, so a
     * watchlist that is polling steadily only pays for what expired.
     */
    public Map<String, Spark> sparklines(Collection<String> symbols) {
        Map<String, Spark> result = new LinkedHashMap<>();
        List<String> misses = new ArrayList<>();

        for (String symbol : symbols) {
            String key = symbol.toUpperCase();
            Spark fresh = sparkCache.peek(key);
            if (fresh != null) {
                result.put(key, fresh);
            } else {
                misses.add(key);
            }
        }
        if (misses.isEmpty()) {
            return result;
        }

        Duration ttl = Range.DAY.cacheTtl();
        try {
            Instant end = Instant.now();
            Instant start = LocalDate.now(ZoneOffset.UTC).minusDays(8).atStartOfDay(ZoneOffset.UTC).toInstant();
            Map<String, List<Candle>> bars = alpaca.bars(misses, Range.DAY.timeframe(), start, end, false);

            for (Map.Entry<String, List<Candle>> entry : bars.entrySet()) {
                Spark spark = toSpark(entry.getKey(), entry.getValue());
                if (spark != null) {
                    sparkCache.put(entry.getKey(), spark, ttl);
                    result.put(entry.getKey(), spark);
                }
            }
        } catch (AlpacaException e) {
            System.out.println("[market] sparkline fetch failed: " + e.getMessage());
        }

        for (String symbol : misses) {
            if (!result.containsKey(symbol)) {
                Spark stale = sparkCache.peekStale(symbol);
                if (stale != null) {
                    result.put(symbol, stale);
                }
            }
        }
        return result;
    }

    private static Spark toSpark(String symbol, List<Candle> bars) {
        if (bars.isEmpty()) {
            return null;
        }
        LocalDate session = sessionOf(bars.get(bars.size() - 1));
        Double baseline = previousSessionClose(bars, session);
        List<Candle> sessionBars = bars.stream().filter(bar -> sessionOf(bar).equals(session)).toList();
        if (sessionBars.isEmpty()) {
            return null;
        }
        return new Spark(symbol, downsample(sessionBars, SPARK_POINTS), baseline);
    }

    /**
     * Reduces a bar list to at most {@code target} closes by even strides,
     * always keeping the final bar so the sparkline ends on the current price.
     */
    static double[] downsample(List<Candle> bars, int target) {
        int size = bars.size();
        if (size <= target) {
            double[] points = new double[size];
            for (int i = 0; i < size; i++) {
                points[i] = bars.get(i).close();
            }
            return points;
        }
        double[] points = new double[target];
        double stride = (size - 1) / (double) (target - 1);
        for (int i = 0; i < target; i++) {
            points[i] = bars.get((int) Math.round(i * stride)).close();
        }
        return points;
    }

    /** Drops every cached quote so the next read hits Alpaca. */
    public void invalidateQuote(String symbol) {
        quoteCache.invalidate(symbol.toUpperCase());
    }
}
