package stockapp.service;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import stockapp.repo.FxRepo;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Currency conversion to NOK, using Norges Bank's published reference rates.
 *
 * <p>Free, no API key, and authoritative for a Norwegian portfolio.
 *
 * <p>The one thing that must not be got wrong is {@code UNIT_MULT}. Norges Bank
 * does not quote every currency per unit: USD and EUR come per single unit, but
 * SEK and DKK are quoted <em>per hundred</em>, flagged by {@code UNIT_MULT=2}.
 * Taking {@code OBS_VALUE} at face value would value a Swedish holding at a
 * hundred times its worth. Everything here is normalised on the way in, so the
 * rest of the app only ever sees "1 unit of base = rate NOK".
 */
public final class FxService {

    private static final String BASE_URL = "https://data.norges-bank.no/api/data/EXR";
    /** The currencies this app can encounter, from the broker exports. */
    private static final List<String> CURRENCIES = List.of("USD", "EUR", "SEK", "DKK", "GBP");
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private final OkHttpClient http;
    private final FxRepo repo;
    private final Cache<String, Map<String, BigDecimal>> cache = new Cache<>();

    public FxService(FxRepo repo) {
        this.repo = repo;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(20))
                .build();
    }

    /**
     * Converts an amount into NOK.
     *
     * <p>NOK passes through untouched. An unknown currency returns null rather
     * than a guess - a wrong total is worse than a visibly missing one.
     */
    public BigDecimal toNok(BigDecimal amount, String currency) {
        if (amount == null) {
            return null;
        }
        if (currency == null || currency.isBlank() || currency.equalsIgnoreCase("NOK")) {
            return amount;
        }
        BigDecimal rate = rate(currency.toUpperCase());
        return rate == null ? null : amount.multiply(rate, MC).setScale(2, RoundingMode.HALF_UP);
    }

    /** NOK per one unit of {@code currency}, or null if unavailable. */
    public BigDecimal rate(String currency) {
        String code = currency.toUpperCase();
        if (code.equals("NOK")) {
            return BigDecimal.ONE;
        }
        Map<String, BigDecimal> rates = latestRates();
        return rates.get(code);
    }

    /**
     * Latest rates for every supported currency.
     *
     * <p>Cached in memory for an hour and mirrored into {@code fx_rate}; Norges
     * Bank publishes once per business day, so anything more eager is wasted
     * traffic. If the fetch fails, the most recent stored rates are used - a
     * yesterday rate is a rounding error, an unpriced portfolio is not.
     */
    public Map<String, BigDecimal> latestRates() {
        Map<String, BigDecimal> fresh = cache.get("latest", Duration.ofHours(1), key -> {
            try {
                Map<String, BigDecimal> fetched = fetch();
                if (!fetched.isEmpty()) {
                    repo.save(LocalDate.now(), fetched);
                }
                return fetched.isEmpty() ? null : fetched;
            } catch (RuntimeException e) {
                System.out.println("[fx] Norges Bank unavailable: " + e.getMessage());
                return null;
            }
        });
        if (fresh != null) {
            return fresh;
        }
        Map<String, BigDecimal> stale = cache.peekStale("latest");
        return stale != null ? stale : repo.latest();
    }

    private Map<String, BigDecimal> fetch() {
        String url = BASE_URL + "/B." + String.join("+", CURRENCIES) + ".NOK.SP"
                + "?lastNObservations=1&format=csv";
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = http.newCall(request).execute()) {
            ResponseBody body = response.body();
            String csv = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            return parse(csv);
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * Parses Norges Bank's semicolon-delimited CSV.
     *
     * <p>Columns are located by header name rather than position: the response
     * carries a dozen metadata columns and their order is not contractual.
     */
    static Map<String, BigDecimal> parse(String csv) {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        String[] lines = csv.split("\r?\n");
        if (lines.length < 2) {
            return rates;
        }

        String[] header = lines[0].split(";");
        int baseIdx = indexOf(header, "BASE_CUR");
        int valueIdx = indexOf(header, "OBS_VALUE");
        int multIdx = indexOf(header, "UNIT_MULT");
        if (baseIdx < 0 || valueIdx < 0) {
            return rates;
        }

        for (int i = 1; i < lines.length; i++) {
            String[] cells = lines[i].split(";");
            if (cells.length <= Math.max(baseIdx, valueIdx)) {
                continue;
            }
            try {
                String base = cells[baseIdx].trim();
                BigDecimal observed = new BigDecimal(cells[valueIdx].trim());
                // UNIT_MULT is the power of ten the quote is scaled by:
                // 0 -> per unit (USD), 2 -> per hundred (SEK, DKK).
                int mult = 0;
                if (multIdx >= 0 && multIdx < cells.length && !cells[multIdx].isBlank()) {
                    mult = Integer.parseInt(cells[multIdx].trim());
                }
                BigDecimal perUnit = mult == 0
                        ? observed
                        : observed.divide(BigDecimal.TEN.pow(mult), MC);
                rates.put(base, perUnit);
            } catch (NumberFormatException | ArithmeticException e) {
                // A single unparseable row must not lose the other currencies.
            }
        }
        return rates;
    }

    private static int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }
}
