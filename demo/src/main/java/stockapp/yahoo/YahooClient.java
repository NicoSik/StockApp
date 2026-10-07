package stockapp.yahoo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Prices for everything Alpaca cannot reach: Oslo Børs, Stockholm, ETFs and
 * Norwegian mutual funds.
 *
 * <p>Alpaca is US equities only, and matching a Norwegian portfolio against it
 * is actively dangerous - it resolves "DNB" to Dun &amp; Bradstreet and has no
 * entry at all for Norsk Hydro. Yahoo has the real Oslo listings, quoted in
 * NOK, and can resolve an ISIN directly.
 *
 * <p>This is an <b>unofficial</b> endpoint. It is not a documented or supported
 * API, and it can change without notice. That is an accepted trade for a local
 * personal tool: when it breaks, holdings fall back to the value their broker
 * reported at import.
 *
 * <p>The batch quote endpoint (v7) now requires a session crumb and answers
 * {@code Unauthorized}, so quotes are fetched per symbol from the chart
 * endpoint and cached by the caller.
 */
public final class YahooClient {

    private static final Logger log = LoggerFactory.getLogger(YahooClient.class);

    private static final String QUOTE_HOST = "https://query1.finance.yahoo.com";
    /** Yahoo rejects requests without a browser-shaped User-Agent. */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36";

    private final OkHttpClient http;

    /** @param shared the app-wide client; see {@code AlpacaClient}. */
    public YahooClient(OkHttpClient shared) {
        this.http = shared.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(20))
                .build();
    }

    /**
     * A live price in the instrument's own currency.
     *
     * @param previousClose the prior session's close, for a day's change. Null
     *                      when the feed omits it, which is not the same as a
     *                      day that moved nothing.
     */
    public record Quote(String symbol, double price, String currency, String name, long asOf,
                        Double previousClose) {
    }

    /** One candidate from a symbol search. */
    public record Match(String symbol, String name, String exchange, String quoteType) {
    }

    // ------------------------------------------------------------------ quote

    public Optional<Quote> quote(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Optional.empty();
        }
        HttpUrl url = HttpUrl.parse(QUOTE_HOST + "/v8/finance/chart/" + symbol.trim())
                .newBuilder()
                .addQueryParameter("interval", "1d")
                .addQueryParameter("range", "1d")
                .build();

        JsonObject json = get(url);
        if (json == null) {
            return Optional.empty();
        }
        JsonObject chart = optObject(json, "chart");
        if (chart == null || !chart.has("result") || !chart.get("result").isJsonArray()) {
            return Optional.empty();
        }
        JsonArray results = chart.getAsJsonArray("result");
        if (results.isEmpty()) {
            return Optional.empty();
        }
        JsonObject meta = optObject(results.get(0).getAsJsonObject(), "meta");
        if (meta == null) {
            return Optional.empty();
        }
        Double price = optDouble(meta, "regularMarketPrice");
        if (price == null) {
            return Optional.empty();
        }
        return Optional.of(new Quote(
                optString(meta, "symbol", symbol),
                price,
                optString(meta, "currency", "").toUpperCase(Locale.ROOT),
                optString(meta, "longName", optString(meta, "shortName", "")),
                (long) (optDouble(meta, "regularMarketTime") == null
                        ? System.currentTimeMillis()
                        : optDouble(meta, "regularMarketTime") * 1000),
                // Yahoo names it chartPreviousClose on this endpoint.
                optDouble(meta, "chartPreviousClose")));
    }

    // ------------------------------------------------------------ history

    /**
     * Daily closes for one symbol between two dates, inclusive, keyed by the
     * trading date on the symbol's own exchange. Empty when Yahoo has nothing.
     */
    public NavigableMap<LocalDate, Double> dailyCloses(String symbol, LocalDate from, LocalDate to) {
        if (symbol == null || symbol.isBlank() || to.isBefore(from)) {
            return new TreeMap<>();
        }
        // A day of margin either side in UTC, so no exchange time zone can push
        // a session out of the window. The result is trimmed back to the range.
        long start = from.minusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long end = to.plusDays(2).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        HttpUrl url = HttpUrl.parse(QUOTE_HOST + "/v8/finance/chart/" + symbol.trim())
                .newBuilder()
                .addQueryParameter("interval", "1d")
                .addQueryParameter("period1", Long.toString(start))
                .addQueryParameter("period2", Long.toString(end))
                .build();

        JsonObject json = get(url);
        if (json == null) {
            return new TreeMap<>();
        }
        return new TreeMap<>(parseDailyCloses(json).subMap(from, true, to, true));
    }

    /**
     * Reads the closes out of a chart response.
     *
     * <p>Each timestamp is the start of a session, so it is dated in the
     * exchange's own time zone, not UTC: a fund priced at midnight in Dublin is
     * stamped 23:00 UTC the day before. Days without a close - funds report
     * null on days they did not price - are skipped.
     */
    static NavigableMap<LocalDate, Double> parseDailyCloses(JsonObject json) {
        NavigableMap<LocalDate, Double> closes = new TreeMap<>();
        JsonObject chart = optObject(json, "chart");
        if (chart == null || !chart.has("result") || !chart.get("result").isJsonArray()) {
            return closes;
        }
        JsonArray results = chart.getAsJsonArray("result");
        if (results.isEmpty() || !results.get(0).isJsonObject()) {
            return closes;
        }
        JsonObject result = results.get(0).getAsJsonObject();
        JsonObject indicators = optObject(result, "indicators");
        if (!result.has("timestamp") || !result.get("timestamp").isJsonArray()
                || indicators == null || !indicators.has("quote") || !indicators.get("quote").isJsonArray()
                || indicators.getAsJsonArray("quote").isEmpty()) {
            return closes;
        }
        JsonObject quote = indicators.getAsJsonArray("quote").get(0).getAsJsonObject();
        if (!quote.has("close") || !quote.get("close").isJsonArray()) {
            return closes;
        }

        ZoneId zone = exchangeZone(optObject(result, "meta"));
        JsonArray timestamps = result.getAsJsonArray("timestamp");
        JsonArray values = quote.getAsJsonArray("close");
        for (int i = 0; i < Math.min(timestamps.size(), values.size()); i++) {
            if (values.get(i).isJsonNull() || timestamps.get(i).isJsonNull()) {
                continue;
            }
            LocalDate day = Instant.ofEpochSecond(timestamps.get(i).getAsLong()).atZone(zone).toLocalDate();
            closes.put(day, values.get(i).getAsDouble());
        }
        return closes;
    }

    /** The exchange's time zone; its current UTC offset if unnamed; UTC as a last resort. */
    private static ZoneId exchangeZone(JsonObject meta) {
        if (meta != null) {
            String name = optString(meta, "exchangeTimezoneName", "");
            if (!name.isBlank()) {
                try {
                    return ZoneId.of(name);
                } catch (DateTimeException e) {
                    // An unknown name; fall through to the offset.
                }
            }
            Double offset = optDouble(meta, "gmtoffset");
            if (offset != null) {
                return ZoneOffset.ofTotalSeconds(offset.intValue());
            }
        }
        return ZoneOffset.UTC;
    }

    // ----------------------------------------------------------------- search

    /**
     * Candidate symbols for a name, ticker or ISIN.
     *
     * <p>ISIN lookup works directly for listed equities, which is the cleanest
     * path when an export happens to carry one. DNB's asset-class layout and
     * hand-entered funds do; everything else arrives as a name or a ticker.
     */
    public List<Match> search(String query) {
        List<Match> matches = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return matches;
        }
        HttpUrl url = HttpUrl.parse(QUOTE_HOST + "/v1/finance/search").newBuilder()
                .addQueryParameter("q", query.trim())
                .addQueryParameter("quotesCount", "10")
                .addQueryParameter("newsCount", "0")
                .build();

        JsonObject json = get(url);
        if (json == null || !json.has("quotes") || !json.get("quotes").isJsonArray()) {
            return matches;
        }
        for (JsonElement element : json.getAsJsonArray("quotes")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject quote = element.getAsJsonObject();
            String symbol = optString(quote, "symbol", null);
            if (symbol == null) {
                continue;
            }
            matches.add(new Match(
                    symbol,
                    optString(quote, "longname", optString(quote, "shortname", symbol)),
                    optString(quote, "exchange", ""),
                    optString(quote, "quoteType", "")));
        }
        return matches;
    }

    /**
     * The Yahoo suffix implied by a holding's currency.
     *
     * <p>The broker tells us the currency, which pins down the exchange far more
     * reliably than a name ever could: a NOK holding is on Oslo Børs, a SEK one
     * is in Stockholm. US listings carry no suffix.
     */
    public static String suffixForCurrency(String currency) {
        if (currency == null) {
            return "";
        }
        return switch (currency.toUpperCase(Locale.ROOT)) {
            case "NOK" -> ".OL";
            case "SEK" -> ".ST";
            case "DKK" -> ".CO";
            case "EUR" -> ".DE";
            case "GBP", "GBX" -> ".L";
            default -> "";
        };
    }

    // ------------------------------------------------------------------- http

    private JsonObject get(HttpUrl url) {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (!response.isSuccessful() || text.isBlank()) {
                return null;
            }
            JsonElement parsed = JsonParser.parseString(text);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (IOException | RuntimeException e) {
            log.warn("Request failed: {}", e.getMessage());
            return null;
        }
    }

    private static JsonObject optObject(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
    }

    private static String optString(JsonObject parent, String key, String fallback) {
        return parent.has(key) && parent.get(key).isJsonPrimitive() ? parent.get(key).getAsString() : fallback;
    }

    private static Double optDouble(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonPrimitive() ? parent.get(key).getAsDouble() : null;
    }
}
