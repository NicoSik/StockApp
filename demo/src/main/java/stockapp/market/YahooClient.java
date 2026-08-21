package stockapp.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Prices for everything Alpaca cannot reach: Oslo Børs, Stockholm, ETFs.
 *
 * <p>Alpaca is US equities only, and matching a Norwegian portfolio against it
 * is actively dangerous - it resolves "DNB" to Dun &amp; Bradstreet and has no
 * entry at all for Norsk Hydro. Yahoo has the real Oslo listings, quoted in
 * NOK, and can resolve an ISIN directly.
 *
 * <p>This is an <b>unofficial</b> endpoint. It is not a documented or supported
 * API, and it can change without notice. That is an accepted trade for a local
 * personal tool: when it breaks, holdings fall back to the value their broker
 * reported at import, which is the same path Norwegian funds already use.
 *
 * <p>The batch quote endpoint (v7) now requires a session crumb and answers
 * {@code Unauthorized}, so quotes are fetched per symbol from the chart
 * endpoint and cached by the caller.
 */
public final class YahooClient {

    private static final String QUOTE_HOST = "https://query1.finance.yahoo.com";
    /** Yahoo rejects requests without a browser-shaped User-Agent. */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36";

    private final OkHttpClient http;

    public YahooClient() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(20))
                .build();
    }

    /** A live price in the instrument's own currency. */
    /**
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

    // ----------------------------------------------------------------- search

    /**
     * Candidate symbols for a name, ticker or ISIN.
     *
     * <p>ISIN lookup works directly for listed equities, which is the cleanest
     * path when an export happens to carry one. DNB's asset-class layout does,
     * but nothing passes it down here yet, so in practice this is fed names and
     * tickers.
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
            System.out.println("[yahoo] request failed: " + e.getMessage());
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
