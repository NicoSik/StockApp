package stockapp.etoro;

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

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client for eToro's public API.
 *
 * <p>Unlike DNB and Nordnet, eToro offers a real personal API, so its holdings
 * arrive live rather than through a monthly export. Authentication is two
 * static keys generated from the account's own settings - no OAuth exchange,
 * no token refresh.
 *
 * <p>The important design point is that <b>this client does not try to price
 * anything</b>. eToro reports a value per position in the account currency,
 * and that value is taken as authoritative. It has to be: an eToro account can
 * hold plain shares, leveraged CFDs, short positions and copy portfolios side
 * by side, and only the first of those is "N shares of X" that could be priced
 * from a ticker. eToro already knows what a leveraged short is worth. All that
 * remains is converting their account currency into NOK.
 */
public final class EtoroClient {

    private static final String BASE_URL = "https://public-api.etoro.com/api/v1";

    private final OkHttpClient http;

    public EtoroClient() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** True when both keys are present; the feature hides itself otherwise. */
    public static boolean configured() {
        return !Config.ETORO_API_KEY.isBlank() && !Config.ETORO_USER_KEY.isBlank();
    }

    /**
     * One position, aggregated across however many individual trades make it up.
     *
     * @param instrumentId eToro's own numeric id - not a ticker, so it has to be
     *                     resolved separately for display
     * @param units        absolute size; {@code direction} carries the sign
     * @param leverage     1 for an ordinary holding, higher for a CFD
     * @param valueAccount current worth in the account's currency, as eToro
     *                     values it. This is the number the app trusts.
     */
    public record Position(long instrumentId,
                           String assetCurrency,
                           BigDecimal units,
                           String direction,
                           BigDecimal leverage,
                           BigDecimal openRate,
                           BigDecimal investedAccount,
                           BigDecimal valueAccount,
                           BigDecimal pnlAccount) {
    }

    /** Account-level totals, in the account's own currency. */
    public record Totals(BigDecimal totalValue,
                         BigDecimal availableCash,
                         BigDecimal balance,
                         BigDecimal currentPnl) {
    }

    public record Portfolio(String accountCurrency, Totals totals, List<Position> positions) {
    }

    // ------------------------------------------------------------- portfolio

    /**
     * The authenticated user's portfolio snapshot.
     *
     * <p>{@code demo} selects the practice account, which is a separate
     * environment with its own key.
     */
    public Portfolio portfolio(boolean demo) {
        String env = demo ? "demo" : "real";
        JsonObject json = get(HttpUrl.parse(BASE_URL + "/trading/info/" + env + "/aggregate-portfolio"));

        // The payload has been seen both bare and wrapped in "data"; accept either
        // rather than break on a shape change.
        JsonObject body = json.has("data") && json.get("data").isJsonObject()
                ? json.getAsJsonObject("data")
                : json;

        String currency = optString(body, "accountCurrency", "USD");
        JsonObject totalsJson = optObject(body, "accountTotals");
        Totals totals = new Totals(
                decimal(totalsJson, "accountTotalValue"),
                decimal(totalsJson, "accountAvailableCash"),
                decimal(totalsJson, "accountBalance"),
                decimal(totalsJson, "accountCurrentPnl"));

        List<Position> positions = new ArrayList<>();
        JsonArray aggregates = optArray(body, "instrumentAggregates");
        if (aggregates != null) {
            for (JsonElement element : aggregates) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject row = element.getAsJsonObject();
                BigDecimal netUnits = decimal(row, "netUnits");
                if (netUnits == null) {
                    continue;
                }
                // Negative net units mean a net short; size is kept absolute and
                // the direction recorded separately so the holdings table cannot
                // present a short as though it were ordinary stock.
                String direction = netUnits.signum() < 0 ? "SHORT" : "LONG";

                positions.add(new Position(
                        optLong(row, "instrumentId"),
                        optString(row, "assetCurrency", currency),
                        netUnits.abs(),
                        direction,
                        decimal(row, "avgLeverage"),
                        decimal(row, "netAvgOpenRate") != null
                                ? decimal(row, "netAvgOpenRate") : decimal(row, "avgOpenRate"),
                        decimal(row, "netInitialExposureAccountCurrency"),
                        // Liquidation value is what the position is actually
                        // worth right now; exposure is notional and would
                        // overstate anything leveraged several times over.
                        firstPresent(row, "liquidationValueAccountCurrency",
                                "netCurrentExposureAccountCurrency", "totalMarginAccountCurrency"),
                        decimal(row, "pnlAssetCurrency")));
            }
        }
        return new Portfolio(currency, totals, positions);
    }

    // ------------------------------------------------------------ instruments

    /**
     * Resolves eToro's numeric instrument ids to something a person recognises.
     *
     * <p>Without this a portfolio reads as a list of numbers. Requests are
     * batched because the endpoint shares a 120-per-minute quota.
     */
    public Map<Long, InstrumentInfo> instruments(Collection<Long> ids) {
        Map<Long, InstrumentInfo> found = new LinkedHashMap<>();
        List<Long> wanted = ids.stream().filter(id -> id > 0).distinct().toList();
        if (wanted.isEmpty()) {
            return found;
        }

        for (int from = 0; from < wanted.size(); from += 50) {
            List<Long> batch = wanted.subList(from, Math.min(wanted.size(), from + 50));
            HttpUrl url = HttpUrl.parse(BASE_URL + "/market-data/instruments/display-data")
                    .newBuilder()
                    .addQueryParameter("instrumentIds",
                            batch.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""))
                    .build();
            try {
                JsonObject json = get(url);
                for (JsonObject row : rowsOf(json)) {
                    long id = optLong(row, "instrumentId");
                    if (id <= 0) {
                        continue;
                    }
                    found.put(id, new InstrumentInfo(
                            id,
                            firstString(row, "symbolFull", "ticker", "symbol"),
                            firstString(row, "instrumentDisplayName", "name", "displayName"),
                            firstString(row, "instrumentTypeDescription", "assetClass", "type")));
                }
            } catch (EtoroException e) {
                // Names are cosmetic; a failure here must not lose the holdings.
                System.out.println("[etoro] instrument lookup failed: " + e.getMessage());
            }
        }
        return found;
    }

    public record InstrumentInfo(long instrumentId, String ticker, String name, String type) {
    }

    /**
     * Pulls rows out of whichever envelope the response uses. Documented
     * examples differ between a bare array, {@code data} and {@code instruments}.
     */
    private static List<JsonObject> rowsOf(JsonObject json) {
        List<JsonObject> rows = new ArrayList<>();
        for (String key : List.of("data", "instruments", "instrumentDisplayDatas", "result")) {
            JsonArray array = optArray(json, key);
            if (array != null) {
                for (JsonElement element : array) {
                    if (element.isJsonObject()) {
                        rows.add(element.getAsJsonObject());
                    }
                }
                return rows;
            }
        }
        return rows;
    }

    /** The raw response, for diagnosing a shape this client does not expect. */
    public String raw(String path) {
        HttpUrl url = HttpUrl.parse(BASE_URL + (path.startsWith("/") ? path : "/" + path));
        if (url == null) {
            throw new EtoroException("Not a valid eToro API path: " + path);
        }
        return execute(url);
    }

    // ------------------------------------------------------------------- http

    private JsonObject get(HttpUrl url) {
        String text = execute(url);
        JsonElement parsed = JsonParser.parseString(text);
        if (parsed.isJsonObject()) {
            return parsed.getAsJsonObject();
        }
        // A bare array is still usable; wrap it so callers have one shape.
        JsonObject wrapper = new JsonObject();
        if (parsed.isJsonArray()) {
            wrapper.add("data", parsed);
        }
        return wrapper;
    }

    private String execute(HttpUrl url) {
        if (!configured()) {
            throw new EtoroException(
                    "eToro is not configured. Set ETORO_API_KEY and ETORO_USER_KEY in .env - "
                            + "generate them under Settings > Trading > API Key Management.");
        }
        Request request = new Request.Builder()
                .url(url)
                // A fresh UUID per request is required, not optional.
                .header("x-request-id", UUID.randomUUID().toString())
                .header("x-api-key", Config.ETORO_API_KEY)
                .header("x-user-key", Config.ETORO_USER_KEY)
                .header("Accept", "application/json")
                .get()
                .build();

        try (Response response = http.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (response.code() == 401 || response.code() == 403) {
                throw new EtoroException("eToro rejected the credentials (HTTP " + response.code()
                        + "). Check the keys are for the right environment - a Real key does not work "
                        + "against the Demo account, or the other way round.");
            }
            if (response.code() == 429) {
                throw new EtoroException("eToro rate limit reached. It allows 60 requests a minute; try again shortly.");
            }
            if (!response.isSuccessful()) {
                throw new EtoroException("eToro returned HTTP " + response.code() + ": " + truncate(text));
            }
            return text;
        } catch (IOException e) {
            throw new EtoroException("Could not reach eToro: " + e.getMessage(), e);
        }
    }

    private static String truncate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    // ------------------------------------------------------------ json helpers

    private static JsonObject optObject(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key) : null;
    }

    private static JsonArray optArray(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonArray()
                ? parent.getAsJsonArray(key) : null;
    }

    private static String optString(JsonObject parent, String key, String fallback) {
        return parent != null && parent.has(key) && parent.get(key).isJsonPrimitive()
                ? parent.get(key).getAsString() : fallback;
    }

    private static String firstString(JsonObject parent, String... keys) {
        for (String key : keys) {
            String value = optString(parent, key, null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static long optLong(JsonObject parent, String key) {
        try {
            return parent != null && parent.has(key) && parent.get(key).isJsonPrimitive()
                    ? parent.get(key).getAsLong() : 0L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static BigDecimal decimal(JsonObject parent, String key) {
        try {
            return parent != null && parent.has(key) && parent.get(key).isJsonPrimitive()
                    ? parent.get(key).getAsBigDecimal() : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The first of several candidate fields that carries a value. */
    private static BigDecimal firstPresent(JsonObject parent, String... keys) {
        for (String key : keys) {
            BigDecimal value = decimal(parent, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
