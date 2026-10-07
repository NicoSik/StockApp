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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

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

    /** @param shared the app-wide client; see {@code AlpacaClient}. */
    public NorgesBankClient(OkHttpClient shared) {
        this.http = shared.newBuilder()
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
        return parse(fetch(currencies, "lastNObservations=1"));
    }

    /**
     * Every rate published for each currency between two dates, inclusive, as
     * NOK per one unit. Business days only: there is no rate for a weekend or
     * a holiday, so callers look up the last rate on or before a date.
     *
     * @throws IllegalStateException when Norges Bank cannot be reached or
     *                               answers with an error
     */
    public Map<String, NavigableMap<LocalDate, BigDecimal>> history(List<String> currencies,
                                                                    LocalDate from, LocalDate to) {
        return parseHistory(fetch(currencies, "startPeriod=" + from + "&endPeriod=" + to));
    }

    private String fetch(List<String> currencies, String query) {
        String url = BASE_URL + "/B." + String.join("+", currencies) + ".NOK.SP"
                + "?" + query + "&format=csv";
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = http.newCall(request).execute()) {
            ResponseBody body = response.body();
            String csv = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            return csv;
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** The latest rate per currency: the last row for it wins. */
    static Map<String, BigDecimal> parse(String csv) {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        for (Rate rate : rows(csv)) {
            rates.put(rate.currency(), rate.nokPerUnit());
        }
        return rates;
    }

    /** Every dated rate per currency, oldest first. Rows without a date are skipped. */
    static Map<String, NavigableMap<LocalDate, BigDecimal>> parseHistory(String csv) {
        Map<String, NavigableMap<LocalDate, BigDecimal>> history = new LinkedHashMap<>();
        for (Rate rate : rows(csv)) {
            if (rate.date() != null) {
                history.computeIfAbsent(rate.currency(), currency -> new TreeMap<>())
                        .put(rate.date(), rate.nokPerUnit());
            }
        }
        return history;
    }

    /** One CSV row, already normalised to NOK per one unit. */
    private record Rate(String currency, LocalDate date, BigDecimal nokPerUnit) {
    }

    /**
     * Parses Norges Bank's semicolon-delimited CSV.
     *
     * <p>Columns are located by header name rather than position: the response
     * carries a dozen metadata columns and their order is not contractual.
     */
    private static List<Rate> rows(String csv) {
        List<Rate> rates = new ArrayList<>();
        String[] lines = csv.split("\r?\n");
        if (lines.length < 2) {
            return rates;
        }

        String[] header = lines[0].split(";");
        int baseIdx = indexOf(header, "BASE_CUR");
        int valueIdx = indexOf(header, "OBS_VALUE");
        int multIdx = indexOf(header, "UNIT_MULT");
        int dateIdx = indexOf(header, "TIME_PERIOD");
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
                LocalDate date = dateIdx >= 0 && dateIdx < cells.length
                        ? LocalDate.parse(cells[dateIdx].trim())
                        : null;
                rates.add(new Rate(base, date, perUnit));
            } catch (NumberFormatException | ArithmeticException | DateTimeParseException e) {
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
