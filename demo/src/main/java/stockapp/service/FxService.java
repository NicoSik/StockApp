package stockapp.service;

import stockapp.norgesbank.NorgesBankClient;
import stockapp.repo.FxRepo;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Currency conversion to NOK, using Norges Bank's published reference rates.
 *
 * <p>Rates arrive already normalised to "1 unit of base = rate NOK" - see
 * {@link NorgesBankClient} for the per-hundred quoting this guards against.
 * This class adds the caching and the fallback to stored rates.
 */
public final class FxService {

    /** The currencies this app can encounter, from the broker exports. */
    private static final List<String> CURRENCIES = List.of("USD", "EUR", "SEK", "DKK", "GBP");
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private final NorgesBankClient norgesBank;
    private final FxRepo repo;
    private final Cache<String, Map<String, BigDecimal>> cache = new Cache<>();

    public FxService(NorgesBankClient norgesBank, FxRepo repo) {
        this.norgesBank = norgesBank;
        this.repo = repo;
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
                Map<String, BigDecimal> fetched = norgesBank.latest(CURRENCIES);
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
}
