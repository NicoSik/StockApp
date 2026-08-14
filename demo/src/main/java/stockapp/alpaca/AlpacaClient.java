package stockapp.alpaca;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import stockapp.Config;
import stockapp.model.Candle;
import stockapp.model.MarketClock;
import stockapp.model.Quote;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Thin, synchronous client for the two Alpaca APIs this app uses:
 * the trading API (assets, clock) and the market data API (bars, snapshots).
 *
 * <p>The client is stateless apart from {@link #activeFeed}, which downgrades
 * itself from {@code sip} to {@code iex} the first time Alpaca reports that the
 * account is not entitled to the consolidated tape. That keeps the app working
 * on a free data plan without any configuration.
 */
public final class AlpacaClient {

    /** Alpaca caps a single bars request at 10,000 rows. */
    private static final int MAX_BARS_PER_PAGE = 10_000;
    /** Snapshot requests are batched to keep URLs a sane length. */
    private static final int SNAPSHOT_BATCH = 100;

    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    private static final LocalTime REGULAR_OPEN = LocalTime.of(9, 30);
    private static final LocalTime REGULAR_CLOSE = LocalTime.of(16, 0);
    private static final LocalTime PRE_MARKET_OPEN = LocalTime.of(4, 0);
    private static final LocalTime AFTER_HOURS_CLOSE = LocalTime.of(20, 0);

    private final OkHttpClient http;
    private volatile String activeFeed;

    public AlpacaClient() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(60))
                .retryOnConnectionFailure(true)
                .build();
        this.activeFeed = Config.DATA_FEED.toLowerCase(Locale.ROOT);
    }

    public String activeFeed() {
        return activeFeed;
    }

    // ------------------------------------------------------------------ clock

    /**
     * Current session state. Alpaca reports only whether the regular session is
     * open, so pre-market and after-hours are derived from the market-local
     * clock on top of that.
     */
    public MarketClock clock() {
        JsonObject json = getJson(HttpUrl.parse(Config.API_URL + "/v2/clock").newBuilder().build());
        boolean isOpen = json.get("is_open").getAsBoolean();
        String nextOpen = optString(json, "next_open");
        String nextClose = optString(json, "next_close");

        ZonedDateTime marketNow = ZonedDateTime.now(MARKET_ZONE);
        String session;
        if (isOpen) {
            session = "OPEN";
        } else if (isWeekday(marketNow) && withinNextOpenDay(marketNow, nextOpen)) {
            LocalTime now = marketNow.toLocalTime();
            if (!now.isBefore(PRE_MARKET_OPEN) && now.isBefore(REGULAR_OPEN)) {
                session = "PRE";
            } else if (!now.isBefore(REGULAR_CLOSE) && now.isBefore(AFTER_HOURS_CLOSE)) {
                session = "AFTER";
            } else {
                session = "CLOSED";
            }
        } else {
            session = "CLOSED";
        }

        return new MarketClock(isOpen, session, nextOpen, nextClose, System.currentTimeMillis());
    }

    private static boolean isWeekday(ZonedDateTime when) {
        return when.getDayOfWeek().getValue() <= 5;
    }

    /**
     * Guards against labelling a holiday as pre-market: if the next regular open
     * is not today, the extended-hours windows do not apply either.
     */
    private static boolean withinNextOpenDay(ZonedDateTime marketNow, String nextOpen) {
        if (nextOpen == null) {
            return true;
        }
        try {
            ZonedDateTime open = ZonedDateTime.parse(nextOpen).withZoneSameInstant(MARKET_ZONE);
            // Before the open it must be today; after the close the next open is
            // tomorrow, which is the normal after-hours case.
            return open.toLocalDate().equals(marketNow.toLocalDate())
                    || marketNow.toLocalTime().isAfter(REGULAR_CLOSE);
        } catch (RuntimeException e) {
            return true;
        }
    }

    // ----------------------------------------------------------------- assets

    /**
     * Every active US equity Alpaca knows about. This is a large response
     * (tens of MB) and is only used by the asset sync, never by a request path.
     */
    public List<Asset> listAssets() {
        HttpUrl url = HttpUrl.parse(Config.API_URL + "/v2/assets").newBuilder()
                .addQueryParameter("status", "active")
                .addQueryParameter("asset_class", "us_equity")
                .build();

        JsonArray array = getJsonArray(url);
        List<Asset> assets = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            JsonObject obj = element.getAsJsonObject();
            String symbol = optString(obj, "symbol");
            String name = optString(obj, "name");
            String exchange = optString(obj, "exchange");
            if (symbol == null || symbol.isBlank()) {
                continue;
            }
            assets.add(new Asset(
                    symbol,
                    name == null || name.isBlank() ? symbol : name,
                    exchange == null ? "UNKNOWN" : exchange,
                    optBool(obj, "tradable"),
                    optBool(obj, "fractionable"),
                    optString(obj, "class")));
        }
        return assets;
    }

    /** One row of the Alpaca assets endpoint. */
    public record Asset(String symbol,
                        String name,
                        String exchange,
                        boolean tradable,
                        boolean fractionable,
                        String assetClass) {
    }

    // -------------------------------------------------------------- snapshots

    /**
     * Latest trade, daily bar and previous close for each symbol.
     *
     * <p>Symbols the API has no data for are simply absent from the result
     * rather than mapped to null, so callers can iterate the map safely.
     */
    public Map<String, Quote> snapshots(Collection<String> symbols) {
        Map<String, Quote> result = new LinkedHashMap<>();
        List<String> all = symbols.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();

        for (int from = 0; from < all.size(); from += SNAPSHOT_BATCH) {
            List<String> batch = all.subList(from, Math.min(all.size(), from + SNAPSHOT_BATCH));
            HttpUrl url = HttpUrl.parse(Config.DATA_URL + "/v2/stocks/snapshots").newBuilder()
                    .addQueryParameter("symbols", String.join(",", batch))
                    .addQueryParameter("feed", activeFeed)
                    .build();

            JsonObject json = getJsonWithFeedFallback(url);
            // Newer API revisions nest the map under "snapshots"; older ones
            // return it at the top level. Accept both.
            JsonObject snapshots = json.has("snapshots") && json.get("snapshots").isJsonObject()
                    ? json.getAsJsonObject("snapshots")
                    : json;

            for (String symbol : batch) {
                if (!snapshots.has(symbol) || !snapshots.get(symbol).isJsonObject()) {
                    continue;
                }
                Quote quote = toQuote(symbol, snapshots.getAsJsonObject(symbol));
                if (quote != null) {
                    result.put(symbol, quote);
                }
            }
        }
        return result;
    }

    private Quote toQuote(String symbol, JsonObject snapshot) {
        JsonObject latestTrade = optObject(snapshot, "latestTrade");
        JsonObject dailyBar = optObject(snapshot, "dailyBar");
        JsonObject prevDailyBar = optObject(snapshot, "prevDailyBar");
        JsonObject minuteBar = optObject(snapshot, "minuteBar");

        Double price = null;
        long asOf = 0L;
        if (latestTrade != null) {
            price = optDouble(latestTrade, "p");
            asOf = toEpochMillis(optString(latestTrade, "t"));
        }
        // Outside trading hours the last trade can be missing; the daily bar
        // close is the correct stand-in.
        if (price == null && dailyBar != null) {
            price = optDouble(dailyBar, "c");
            asOf = toEpochMillis(optString(dailyBar, "t"));
        }
        if (price == null && prevDailyBar != null) {
            price = optDouble(prevDailyBar, "c");
            asOf = toEpochMillis(optString(prevDailyBar, "t"));
        }
        if (price == null) {
            return null;
        }

        Double previousClose = prevDailyBar == null ? null : optDouble(prevDailyBar, "c");
        Double open = dailyBar == null ? null : optDouble(dailyBar, "o");
        Double high = dailyBar == null ? null : optDouble(dailyBar, "h");
        Double low = dailyBar == null ? null : optDouble(dailyBar, "l");
        Double vwap = dailyBar == null ? null : optDouble(dailyBar, "vw");
        long volume = dailyBar == null ? 0L : optLong(dailyBar, "v");

        if (asOf == 0L && minuteBar != null) {
            asOf = toEpochMillis(optString(minuteBar, "t"));
        }
        if (asOf == 0L) {
            asOf = System.currentTimeMillis();
        }

        return Quote.of(symbol, price, previousClose, open, high, low, vwap, volume, asOf);
    }

    // ------------------------------------------------------------------- bars

    /**
     * Historical bars for a single symbol, following pagination to the end.
     *
     * @param adjusted apply split adjustment - correct for daily and weekly
     *                 history, meaningless for intraday
     */
    public List<Candle> bars(String symbol, String timeframe, Instant start, Instant end, boolean adjusted) {
        return bars(List.of(symbol), timeframe, start, end, adjusted).getOrDefault(symbol, List.of());
    }

    /**
     * Historical bars for many symbols in a single request.
     *
     * <p>This is what keeps the watchlist cheap: thirty sparklines cost one
     * round trip rather than thirty. Pagination spans the whole result set, so
     * every page is merged into the same per-symbol lists.
     */
    public Map<String, List<Candle>> bars(Collection<String> symbols, String timeframe,
                                          Instant start, Instant end, boolean adjusted) {
        Map<String, List<Candle>> bySymbol = new LinkedHashMap<>();
        List<String> requested = symbols.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (requested.isEmpty()) {
            return bySymbol;
        }

        String pageToken = null;
        do {
            HttpUrl.Builder builder = HttpUrl.parse(Config.DATA_URL + "/v2/stocks/bars").newBuilder()
                    .addQueryParameter("symbols", String.join(",", requested))
                    .addQueryParameter("timeframe", timeframe)
                    .addQueryParameter("start", start.toString())
                    .addQueryParameter("end", end.toString())
                    .addQueryParameter("limit", String.valueOf(MAX_BARS_PER_PAGE))
                    .addQueryParameter("sort", "asc")
                    .addQueryParameter("feed", activeFeed);
            if (adjusted) {
                builder.addQueryParameter("adjustment", "split");
            }
            if (pageToken != null) {
                builder.addQueryParameter("page_token", pageToken);
            }

            JsonObject json = getJsonWithFeedFallback(builder.build());
            JsonObject barsBySymbol = optObject(json, "bars");
            if (barsBySymbol != null) {
                for (String symbol : barsBySymbol.keySet()) {
                    if (!barsBySymbol.get(symbol).isJsonArray()) {
                        continue;
                    }
                    List<Candle> candles = bySymbol.computeIfAbsent(symbol, k -> new ArrayList<>());
                    for (JsonElement element : barsBySymbol.getAsJsonArray(symbol)) {
                        Candle candle = toCandle(element.getAsJsonObject());
                        if (candle != null) {
                            candles.add(candle);
                        }
                    }
                }
            }
            pageToken = optString(json, "next_page_token");
        } while (pageToken != null && !pageToken.isBlank());

        return bySymbol;
    }

    private static Candle toCandle(JsonObject bar) {
        long time = toEpochMillis(optString(bar, "t"));
        Double close = optDouble(bar, "c");
        if (time == 0L || close == null) {
            return null;
        }
        Double open = optDouble(bar, "o");
        Double high = optDouble(bar, "h");
        Double low = optDouble(bar, "l");
        return new Candle(
                time,
                open == null ? close : open,
                high == null ? close : high,
                low == null ? close : low,
                close,
                optLong(bar, "v"));
    }

    // ------------------------------------------------------------------- http

    private JsonObject getJsonWithFeedFallback(HttpUrl url) {
        try {
            return getJson(url);
        } catch (AlpacaException e) {
            if (!e.isSubscriptionProblem() || activeFeed.equals("iex")) {
                throw e;
            }
            System.out.println("[alpaca] account is not entitled to the '" + activeFeed
                    + "' feed; falling back to 'iex' for the rest of this run.");
            activeFeed = "iex";
            HttpUrl retry = url.newBuilder().setQueryParameter("feed", "iex").build();
            return getJson(retry);
        }
    }

    private JsonObject getJson(HttpUrl url) {
        JsonElement element = execute(url);
        if (!element.isJsonObject()) {
            throw new AlpacaException(200, element.toString(), "Expected a JSON object from " + redact(url));
        }
        return element.getAsJsonObject();
    }

    private JsonArray getJsonArray(HttpUrl url) {
        JsonElement element = execute(url);
        if (!element.isJsonArray()) {
            throw new AlpacaException(200, element.toString(), "Expected a JSON array from " + redact(url));
        }
        return element.getAsJsonArray();
    }

    private JsonElement execute(HttpUrl url) {
        Request request = new Request.Builder()
                .url(url)
                .header("APCA-API-KEY-ID", Config.API_KEY_ID)
                .header("APCA-API-SECRET-KEY", Config.API_SECRET_KEY)
                .header("Accept", "application/json")
                .get()
                .build();

        try (Response response = http.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw new AlpacaException(response.code(), text,
                        "Alpaca " + response.code() + " for " + redact(url) + ": " + truncate(text));
            }
            return JsonParser.parseString(text);
        } catch (IOException e) {
            throw new AlpacaException("Could not reach Alpaca at " + redact(url) + ": " + e.getMessage(), e);
        }
    }

    /** Keeps credentials out of logs even though they travel in headers today. */
    private static String redact(HttpUrl url) {
        return url.newBuilder().removeAllQueryParameters("page_token").build().toString();
    }

    private static String truncate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    // ------------------------------------------------------------ json helpers

    private static JsonObject optObject(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
    }

    private static String optString(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonPrimitive() ? parent.get(key).getAsString() : null;
    }

    private static Double optDouble(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonPrimitive() ? parent.get(key).getAsDouble() : null;
    }

    private static long optLong(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonPrimitive() ? parent.get(key).getAsLong() : 0L;
    }

    private static boolean optBool(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonPrimitive() && parent.get(key).getAsBoolean();
    }

    static long toEpochMillis(String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(isoTimestamp).toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    public void shutdown() {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
        try {
            if (http.cache() != null) {
                http.cache().close();
            }
        } catch (IOException ignored) {
            // Nothing useful to do while shutting down.
        }
        try {
            http.dispatcher().executorService().awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
