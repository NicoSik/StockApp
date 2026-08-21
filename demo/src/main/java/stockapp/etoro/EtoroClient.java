package stockapp.etoro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import stockapp.Config;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
                // eToro's API accepts the HTTP/2 upgrade during TLS negotiation
                // and then never responds: the same request answers in 0.2s over
                // HTTP/1.1 and hangs until timeout over HTTP/2. OkHttp prefers
                // HTTP/2 by default, so it has to be pinned here or every call
                // stalls for the full read timeout.
                .protocols(List.of(Protocol.HTTP_1_1))
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
        try {
            return aggregatePortfolio(demo);
        } catch (EtoroException e) {
            if (e.status() != 404) {
                throw e;
            }
            // Not every account has this endpoint: eToro answers 404 rather than
            // an empty portfolio. /pnl is the one that does answer, so a 404 here
            // is a routing fact, not an error worth showing anyone.
            System.out.println("[etoro] aggregate-portfolio is not available on this account; using /pnl");
            return pnlPortfolio(demo);
        }
    }

    private Portfolio aggregatePortfolio(boolean demo) {
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

    /**
     * The same portfolio, rebuilt from {@code /pnl}.
     *
     * <p>Where {@code /aggregate-portfolio} reports one row per instrument,
     * {@code /pnl} reports every open position separately, so the aggregation
     * has to happen here: two lots of the same share are one holding.
     *
     * <p>Copy-trading mirrors are folded in beside the positions opened by
     * hand. A mirror holds real positions in real instruments, and it can
     * dwarf the rest - hundreds of them against a handful opened directly - so
     * leaving them out would report a sliver of the portfolio as though it were
     * the whole of it.
     */
    public Portfolio pnlPortfolio(boolean demo) {
        String env = demo ? "demo" : "real";
        return parsePnl(get(HttpUrl.parse(BASE_URL + "/trading/info/" + env + "/pnl")));
    }

    /** Split from the request so the mapping can be exercised on a saved response. */
    static Portfolio parsePnl(JsonObject json) {
        JsonObject body = optObject(json, "clientPortfolio");
        if (body == null) {
            body = json.has("data") && json.get("data").isJsonObject() ? json.getAsJsonObject("data") : json;
        }
        String currency = currencyName(optLong(body, "accountCurrencyId"));

        List<JsonObject> rows = new ArrayList<>();
        collectPositions(optArray(body, "positions"), rows);
        JsonArray mirrors = optArray(body, "mirrors");
        if (mirrors != null) {
            for (JsonElement element : mirrors) {
                if (element.isJsonObject()) {
                    collectPositions(optArray(element.getAsJsonObject(), "positions"), rows);
                }
            }
        }

        Map<Long, Aggregate> byInstrument = new LinkedHashMap<>();
        for (JsonObject row : rows) {
            long instrumentId = optLong(row, "instrumentID");
            if (instrumentId <= 0) {
                instrumentId = optLong(row, "instrumentId");
            }
            JsonObject pnl = optObject(row, "unrealizedPnL");
            BigDecimal units = decimal(row, "units");
            BigDecimal margin = decimal(pnl, "marginInAccountCurrency");
            if (instrumentId <= 0 || units == null || margin == null) {
                continue;
            }
            BigDecimal profit = decimal(pnl, "pnL");
            if (profit == null) {
                profit = BigDecimal.ZERO;
            }
            BigDecimal openRate = decimal(row, "openRate");

            Aggregate aggregate = byInstrument.computeIfAbsent(instrumentId, id -> new Aggregate());
            // A sell is a short; netting the units signed keeps a long and a
            // short in the same instrument from reading as one large holding.
            boolean isBuy = !row.has("isBuy") || row.get("isBuy").getAsBoolean();
            aggregate.units = aggregate.units.add(isBuy ? units : units.negate());
            aggregate.invested = aggregate.invested.add(margin);
            aggregate.pnl = aggregate.pnl.add(profit);
            if (openRate != null) {
                aggregate.notional = aggregate.notional.add(openRate.multiply(units));
            }
            BigDecimal leverage = decimal(row, "leverage");
            if (leverage != null) {
                aggregate.leverageWeighted = aggregate.leverageWeighted.add(leverage.multiply(margin));
            }
        }

        List<Position> positions = new ArrayList<>();
        BigDecimal totalValue = BigDecimal.ZERO;
        for (Map.Entry<Long, Aggregate> entry : byInstrument.entrySet()) {
            Aggregate aggregate = entry.getValue();
            // What it would be worth if closed now. Deriving it from the money
            // committed plus the profit - rather than from exposure - is what
            // keeps it right for a short, where exposure moves the wrong way.
            BigDecimal value = aggregate.invested.add(aggregate.pnl);
            totalValue = totalValue.add(value);

            BigDecimal units = aggregate.units.abs();
            positions.add(new Position(
                    entry.getKey(),
                    currency,
                    units,
                    aggregate.units.signum() < 0 ? "SHORT" : "LONG",
                    aggregate.invested.signum() == 0 ? null
                            : aggregate.leverageWeighted.divide(aggregate.invested, 4, RoundingMode.HALF_UP),
                    units.signum() == 0 ? null : aggregate.notional.divide(units, 6, RoundingMode.HALF_UP),
                    aggregate.invested,
                    value,
                    aggregate.pnl));
        }

        Totals totals = new Totals(
                totalValue,
                // /pnl reports no cash figure. "credit" sits next to
                // "bonusCredit" and is not the uninvested balance, so nothing is
                // reported rather than a zero that would read as an empty wallet.
                null,
                null,
                decimal(body, "unrealizedPnL"));
        return new Portfolio(currency, totals, positions);
    }

    /** Running totals for one instrument while positions are folded together. */
    private static final class Aggregate {
        private BigDecimal units = BigDecimal.ZERO;
        private BigDecimal invested = BigDecimal.ZERO;
        private BigDecimal pnl = BigDecimal.ZERO;
        private BigDecimal notional = BigDecimal.ZERO;
        private BigDecimal leverageWeighted = BigDecimal.ZERO;
    }

    private static void collectPositions(JsonArray array, List<JsonObject> into) {
        if (array == null) {
            return;
        }
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                into.add(element.getAsJsonObject());
            }
        }
    }

    /**
     * {@code /pnl} identifies currencies by number where the rest of the API
     * uses names. Only the ids actually seen are mapped; an unknown one falls
     * back to USD, which is what an eToro account is denominated in unless it
     * was opened otherwise.
     */
    private static String currencyName(long currencyId) {
        return switch ((int) currencyId) {
            case 2 -> "GBP";
            case 3 -> "EUR";
            default -> "USD";
        };
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
            HttpUrl url = HttpUrl.parse(BASE_URL + "/market-data/instruments")
                    .newBuilder()
                    .addQueryParameter("instrumentIds",
                            batch.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""))
                    .build();
            try {
                JsonObject json = get(url);
                for (JsonObject row : rowsOf(json)) {
                    // The metadata endpoint spells it instrumentID; the portfolio
                    // endpoint spells the same thing instrumentId. Accept both.
                    long id = optLong(row, "instrumentID");
                    if (id <= 0) {
                        id = optLong(row, "instrumentId");
                    }
                    if (id <= 0) {
                        continue;
                    }
                    found.put(id, new InstrumentInfo(
                            id,
                            firstString(row, "symbolFull", "ticker", "symbol"),
                            firstString(row, "instrumentDisplayName", "name", "displayName"),
                            // A numeric category, not a description.
                            (int) optLong(row, "instrumentTypeID")));
                }
            } catch (EtoroStalledException e) {
                // Do not try the next batch. eToro's throttling is a silent stall
                // keyed to the account, and it deepens the more it is poked, so a
                // loop that carries on "just to try" costs a full read timeout per
                // batch and makes the block worse for every later call. Names are
                // cosmetic - abandoning them is much cheaper than that.
                System.out.println("[etoro] instrument lookup stalled; abandoning the remaining "
                        + (wanted.size() - from) + " id(s) rather than making it worse.");
                break;
            } catch (EtoroException e) {
                // Names are cosmetic; a failure here must not lose the holdings.
                System.out.println("[etoro] instrument lookup failed: " + e.getMessage());
            }
        }
        return found;
    }

    /** @param typeId eToro's numeric instrument category; 0 when unknown */
    public record InstrumentInfo(long instrumentId, String ticker, String name, int typeId) {
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

    /**
     * The raw response, for diagnosing a shape this client does not expect.
     *
     * <p>Every capture is also written to {@code logs/etoro/}. That is not
     * incidental: a sample of the real account's {@code /pnl} response was once
     * seen only in a browser and lost when eToro started throttling, and mapping
     * the endpoint has been blocked on getting another one ever since. Under
     * throttling a successful response can be a once-an-hour event, so it is
     * saved the moment it arrives rather than trusted to a scrollback buffer.
     */
    public String raw(String path) {
        HttpUrl url = HttpUrl.parse(BASE_URL + (path.startsWith("/") ? path : "/" + path));
        if (url == null) {
            throw new EtoroException("Not a valid eToro API path: " + path);
        }
        String text = execute(url);
        save(path, text);
        return text;
    }

    /** Writes a capture aside. Failing to save must never fail the request. */
    private static void save(String path, String text) {
        try {
            Path dir = Path.of("logs", "etoro");
            Files.createDirectories(dir);
            String slug = path.replaceAll("^/+", "").replaceAll("[^A-Za-z0-9._-]+", "-");
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path file = dir.resolve(stamp + "-" + slug + ".json");
            Files.writeString(file, text, StandardCharsets.UTF_8);
            System.out.println("[etoro] captured " + text.length() + " chars to " + file);
        } catch (IOException | RuntimeException e) {
            System.out.println("[etoro] could not save the capture: " + e);
        }
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
                        + "against the Demo account, or the other way round.", response.code());
            }
            if (response.code() == 429) {
                throw new EtoroException(
                        "eToro rate limit reached. It allows 60 requests a minute; try again shortly.",
                        response.code());
            }
            if (!response.isSuccessful()) {
                throw new EtoroException("eToro returned HTTP " + response.code() + ": " + truncate(text),
                        response.code());
            }
            return text;
        } catch (InterruptedIOException e) {
            // A timeout here reads like a network fault and is almost never one:
            // an unreachable host answers in milliseconds, and so does a rejected
            // key. Measured against the live API, a request carrying this
            // account's x-user-key stalls while an otherwise identical one
            // without it gets an instant 401 - so the stall is eToro declining to
            // serve the account, not the network, and not something a different
            // API key would fix.
            //
            // No probe is fired to confirm that. Anything sent to check would
            // carry the same user-key, and the stall was seen to spread across
            // endpoints as more requests were made. The one useful action here is
            // to stop, so this must not itself make a request.
            throw new EtoroStalledException(
                    "eToro accepted the connection for " + url.encodedPath() + " and sent nothing back "
                            + "within " + http.readTimeoutMillis() / 1000 + "s. That is how it signals "
                            + "throttling - it does not return 429 - and it is tied to the account, not "
                            + "to the API key, so regenerating the key will not clear it and retrying "
                            + "makes it worse. Stop calling eToro for a few hours, then try one single "
                            + "request. If a request without the keys still gets a fast 401, the network "
                            + "is fine and this is only about waiting.", e);
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
