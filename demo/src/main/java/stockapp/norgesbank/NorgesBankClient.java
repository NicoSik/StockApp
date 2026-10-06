package stockapp.norgesbank;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Norges Bank's published exchange rates against NOK.
 *
 * <p>Free, no API key, and authoritative for a Norwegian portfolio. The data
 * API speaks SDMX: {@code EXR/B.USD+EUR.NOK.SP} is business-daily ({@code B})
 * spot ({@code SP}) rates for USD and EUR quoted in NOK.
 *
 * <p>The one thing that must not be got wrong is {@code UNIT_MULT}. Norges Bank
 * does not quote every currency per unit: USD and EUR come per single unit, but
 * SEK and DKK are quoted <em>per hundred</em>, flagged by {@code UNIT_MULT=2}.
 * Taking {@code OBS_VALUE} at face value would value a Swedish holding at a
 * hundred times its worth. Everything is normalised on the way in, so callers
 * only ever see "1 unit of base = rate NOK".
 */
public final class NorgesBankClient {

    private static final String BASE_URL = "https://data.norges-bank.no/api/data/EXR";
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private final OkHttpClient http;

    public NorgesBankClient() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(20))
                .build();
    }

    /**
     * The most recent published rate for each currency, as NOK per one unit.
     *
     * <p>Rates are published once per business day, so on a weekend or holiday
     * this is the last working day's rate.
     *
     * @throws IllegalStateException when Norges Bank cannot be reached or
     *                               answers with an error
     */
    public Map<String, BigDecimal> latest(List<String> currencies) {
        String url = BASE_URL + "/B." + String.join("+", currencies) + ".NOK.SP"
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
