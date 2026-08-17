package stockapp.market;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns a line from a broker export into a priceable instrument.
 *
 * <p>Nothing reaches this class with an ISIN - Nordnet's export has none, and
 * the one DNB layout that does is not plumbed through for it - so identity has
 * to be recovered from a name or a ticker. Doing that naively is how you end
 * up pricing a DNB Bank holding as Dun &amp; Bradstreet, or a Norwegian fund
 * as its Danish share class in DKK.
 *
 * <p>Two things make it safe:
 *
 * <ol>
 *   <li><b>Currency narrows the exchange.</b> A NOK holding is on Oslo Børs, a
 *       SEK one is in Stockholm. That eliminates almost every wrong candidate
 *       before a price is even fetched.</li>
 *   <li><b>The export's own price is the proof.</b> Every row carries what the
 *       broker thinks the thing is worth. If the resolved symbol's live price
 *       agrees, the match is almost certainly right; if it does not, the match
 *       is refused and handed to a human.</li>
 * </ol>
 *
 * <p>That second check is what caught a real mismatch: the export said 2,10
 * and the live price was 12,60, a ratio of exactly six - an unadjusted reverse
 * split. Auto-accepting it would have silently understated the holding.
 */
public final class InstrumentResolver {

    /**
     * How far the live price may differ from the export's before the match is
     * rejected. Generous enough to absorb an export taken hours ago or a
     * fast-moving small cap, tight enough that a different company fails.
     */
    private static final double PRICE_TOLERANCE = 0.06;

    private final YahooClient yahoo;

    public InstrumentResolver(YahooClient yahoo) {
        this.yahoo = yahoo;
    }

    public enum Status {
        /** Symbol found and its price agrees with the export. Safe to use. */
        CONFIRMED,
        /** Symbol found but the price disagrees. Needs a human to look. */
        NEEDS_REVIEW,
        /** Nothing plausible found. Needs a manual mapping. */
        UNRESOLVED
    }

    /**
     * @param livePrice the price found for {@code symbol}, for display in the
     *                  reconcile screen so the user can see both numbers
     */
    public record Resolution(Status status,
                             String symbol,
                             String name,
                             String currency,
                             Double livePrice,
                             Double expectedPrice,
                             String note) {

        public boolean usable() {
            return status == Status.CONFIRMED;
        }
    }

    /**
     * Resolves one export row.
     *
     * @param ticker        the broker's ticker if it supplies one (DNB does),
     *                      otherwise null
     * @param name          the broker's display name (Nordnet's only identifier)
     * @param currency      the holding's currency, which implies the exchange
     * @param expectedPrice the broker's own last price, used to verify
     */
    public Resolution resolve(String ticker, String name, String currency, BigDecimal expectedPrice) {
        Double expected = expectedPrice == null ? null : expectedPrice.doubleValue();
        String suffix = YahooClient.suffixForCurrency(currency);

        // A ticker is far stronger evidence than a name; DNB gives one, and for
        // an Oslo listing "TEL" + ".OL" is unambiguous.
        if (ticker != null && !ticker.isBlank()) {
            String candidate = ticker.trim().toUpperCase(Locale.ROOT) + suffix;
            Resolution byTicker = verify(candidate, currency, expected);
            if (byTicker.status != Status.UNRESOLVED) {
                return byTicker;
            }
        }

        // Otherwise search by name. Try the name as written first, then again
        // with a trailing share-class letter removed - Nordnet writes
        // "Oscar Health A", which finds nothing until the " A" comes off.
        // Every candidate is tried, and a confirmed match wins over a doubtful
        // one however they were ordered. Returning the first non-UNRESOLVED
        // answer instead would stop at whichever share class the search happened
        // to rank first - and a fund search returns six of them, differing only
        // by a trailing letter. The price check is what tells them apart, so it
        // has to be allowed to see more than one.
        Resolution doubtful = null;
        for (String query : queriesFor(name)) {
            for (String symbol : candidates(yahoo.search(query), suffix)) {
                Resolution attempt = verify(symbol, currency, expected);
                if (attempt.status == Status.CONFIRMED) {
                    return attempt;
                }
                if (doubtful == null && attempt.status == Status.NEEDS_REVIEW) {
                    doubtful = attempt;
                }
            }
        }
        if (doubtful != null) {
            return doubtful;
        }

        return new Resolution(Status.UNRESOLVED, null, name, currency, null, expected,
                "No matching instrument found");
    }

    /**
     * Looks up a symbol the user chose by hand.
     *
     * <p>An overridden row still carries the name of the match that was
     * <em>rejected</em>, so storing that alongside the corrected symbol would
     * label the right instrument with the wrong company - LIDR appearing as
     * "AudioEye, Inc." because AudioEye is what the failed lookup returned.
     */
    public Optional<YahooClient.Quote> describe(String symbol) {
        return symbol == null || symbol.isBlank() ? Optional.empty() : yahoo.quote(symbol.trim());
    }

    /** The name as given, then without a trailing share-class letter. */
    static List<String> queriesFor(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String trimmed = name.trim();
        String stripped = trimmed.replaceFirst("\\s+[A-Z]$", "");
        return stripped.equals(trimmed) ? List.of(trimmed) : List.of(trimmed, stripped);
    }

    /**
     * The instrument types a holding can legitimately be. Anything else that
     * Yahoo's search happens to return is not what the broker is holding.
     */
    private static final List<String> TRADABLE_TYPES =
            List.of("EQUITY", "ETF", "MUTUALFUND", "INDEX", "CRYPTOCURRENCY");

    /**
     * An OCC option symbol: underlying, 6-digit expiry, C or P, 8-digit strike -
     * for example {@code AEYE260821C00006000}.
     */
    private static final java.util.regex.Pattern OPTION_SYMBOL =
            java.util.regex.Pattern.compile("^[A-Z]{1,6}\\d{6}[CP]\\d{8}$");

    /**
     * How many candidates one search is allowed to cost in quote requests.
     * Yahoo is an unofficial endpoint; a wide search must not turn into a
     * dozen calls.
     */
    private static final int MAX_CANDIDATES = 6;

    /**
     * The candidates worth trying, best first: symbols carrying the suffix the
     * currency implies, then everything else the search returned.
     *
     * <p>The suffix is a preference rather than a requirement, because it is
     * only a reliable signal for shares. A Norwegian <em>fund</em> priced in
     * NOK is listed as {@code 0P0000PS3V.IR}, so demanding {@code .OL} rejected
     * every mutual fund outright. Ordering rather than filtering keeps share
     * resolution exactly as it was - anything that matched before is still
     * tried first - while letting a fund reach the checks that can identify it.
     *
     * <p>Derivatives are excluded first, and that filter is not optional.
     * Searching "Oklo A" returns an OKLO <em>option</em> ahead of the share, and
     * an option on a stock frequently trades near the stock's own price - so it
     * can slip past the price check and be auto-confirmed. Filtering on the
     * instrument type is what makes the price check trustworthy rather than
     * merely usually right.
     */
    static List<String> candidates(List<YahooClient.Match> matches, String suffix) {
        List<String> preferred = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (YahooClient.Match match : matches) {
            if (!isTradableInstrument(match)) {
                continue;
            }
            String symbol = match.symbol();
            boolean fitsCurrency = suffix.isEmpty()
                    ? !symbol.matches(".*\\.[A-Z]{1,3}$")
                    : symbol.endsWith(suffix);
            (fitsCurrency ? preferred : rest).add(symbol);
        }
        preferred.addAll(rest);
        return preferred.size() > MAX_CANDIDATES ? List.copyOf(preferred.subList(0, MAX_CANDIDATES)) : preferred;
    }

    private static boolean isTradableInstrument(YahooClient.Match match) {
        if (OPTION_SYMBOL.matcher(match.symbol()).matches()) {
            return false;
        }
        String type = match.quoteType();
        // An unknown or absent type is allowed through - the price check is
        // still the backstop - but a type we know is wrong is rejected outright.
        return type == null || type.isBlank()
                || TRADABLE_TYPES.contains(type.toUpperCase(Locale.ROOT));
    }

    /** Fetches the symbol's price and compares it with the broker's. */
    private Resolution verify(String symbol, String currency, Double expected) {
        Optional<YahooClient.Quote> quote = yahoo.quote(symbol);
        if (quote.isEmpty()) {
            return new Resolution(Status.UNRESOLVED, null, null, currency, null, expected, "No price for " + symbol);
        }
        YahooClient.Quote found = quote.get();

        // A currency mismatch means the wrong listing or the wrong share class,
        // whatever the name says. This is the check that rejects a Danish fund
        // class standing in for a Norwegian one.
        if (currency != null && !currency.isBlank()
                && !found.currency().isBlank()
                && !found.currency().equalsIgnoreCase(currency)) {
            return new Resolution(Status.NEEDS_REVIEW, found.symbol(), found.name(), found.currency(),
                    found.price(), expected,
                    "Currency mismatch: export says " + currency + ", " + found.symbol()
                            + " trades in " + found.currency());
        }

        if (expected == null || expected == 0) {
            // Nothing to verify against; found but unproven.
            return new Resolution(Status.NEEDS_REVIEW, found.symbol(), found.name(), found.currency(),
                    found.price(), null, "No price in the export to verify against");
        }

        double drift = Math.abs(found.price() - expected) / expected;
        if (drift <= PRICE_TOLERANCE) {
            return new Resolution(Status.CONFIRMED, found.symbol(), found.name(), found.currency(),
                    found.price(), expected, null);
        }
        return new Resolution(Status.NEEDS_REVIEW, found.symbol(), found.name(), found.currency(),
                found.price(), expected,
                "Price differs by %.0f%% - possible split, stale export, or wrong instrument"
                        .formatted(drift * 100));
    }
}
