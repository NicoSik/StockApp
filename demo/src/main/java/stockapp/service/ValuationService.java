package stockapp.service;

import stockapp.market.YahooClient;
import stockapp.repo.AccountRepo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Values every account in NOK.
 *
 * <p>Two valuation paths, and the difference is reported rather than hidden:
 *
 * <ul>
 *   <li><b>Live</b> - the instrument has a verified symbol, so a current price
 *       is fetched and converted to NOK. Roughly two thirds of a typical
 *       Norwegian portfolio: Oslo Børs shares and US equities.</li>
 *   <li><b>As of import</b> - no free price source exists, which is the case
 *       for Norwegian mutual funds. The value the broker reported is used, and
 *       the date it came from is carried alongside it.</li>
 * </ul>
 *
 * <p>Presenting a three-week-old fund NAV as though it were current would be a
 * small lie that compounds. The split is surfaced instead, so a total reads
 * "614 696 NOK, 68% live, 32% as of 14 Aug".
 */
public final class ValuationService {

    private final AccountRepo accounts;
    private final YahooClient yahoo;
    private final FxService fx;
    private final Cache<String, BigDecimal> priceCache = new Cache<>();

    public ValuationService(AccountRepo accounts, YahooClient yahoo, FxService fx) {
        this.accounts = accounts;
        this.yahoo = yahoo;
        this.fx = fx;
    }

    /**
     * @param leverage  above 1 for a CFD, null for an ordinary holding
     * @param direction LONG or SHORT where the distinction exists
     */
    public record ValuedHolding(String symbol,
                                String name,
                                String kind,
                                String currency,
                                BigDecimal quantity,
                                BigDecimal avgCost,
                                BigDecimal price,
                                BigDecimal valueNok,
                                BigDecimal costBasisNok,
                                BigDecimal gainNok,
                                BigDecimal gainPercent,
                                BigDecimal weight,
                                boolean live,
                                String accountName,
                                BigDecimal leverage,
                                String direction) {
    }

    public record AccountValuation(int id,
                                   String name,
                                   String broker,
                                   LocalDate asOf,
                                   BigDecimal valueNok,
                                   int holdingCount,
                                   boolean simulated,
                                   List<ValuedHolding> holdings) {
    }

    /**
     * @param totalNok     real money only; simulated accounts are excluded
     * @param simulatedNok practice money, reported separately so it can be shown
     *                     without ever being added to a net worth
     */
    public record Totals(BigDecimal totalNok,
                         BigDecimal liveNok,
                         BigDecimal asOfNok,
                         BigDecimal livePercent,
                         BigDecimal gainNok,
                         BigDecimal costBasisNok,
                         BigDecimal simulatedNok,
                         LocalDate oldestAsOf,
                         int accountCount,
                         int holdingCount,
                         List<AccountValuation> accounts,
                         List<ValuedHolding> holdings,
                         Map<String, BigDecimal> fxRates) {
    }

    /** Values every account against its most recent snapshot. */
    public Totals valueEverything() {
        List<AccountValuation> valued = new ArrayList<>();
        List<ValuedHolding> allHoldings = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal live = BigDecimal.ZERO;
        BigDecimal costBasis = BigDecimal.ZERO;
        BigDecimal measured = BigDecimal.ZERO;
        BigDecimal simulated = BigDecimal.ZERO;
        LocalDate oldest = null;

        for (AccountRepo.Account account : accounts.listAccounts()) {
            Optional<AccountRepo.Snapshot> snapshot = accounts.latestSnapshot(account.id());
            if (snapshot.isEmpty()) {
                valued.add(new AccountValuation(account.id(), account.name(), account.broker(),
                        null, BigDecimal.ZERO, 0, account.simulated(), List.of()));
                continue;
            }

            AccountRepo.Snapshot latest = snapshot.get();
            List<ValuedHolding> holdings = new ArrayList<>();
            BigDecimal accountTotal = BigDecimal.ZERO;
            BigDecimal accountCost = BigDecimal.ZERO;
            // Value of the rows whose cost is known. Gain is measured against
            // this, never against the account total, so a holding with no cost
            // basis cannot masquerade as pure profit.
            BigDecimal accountMeasured = BigDecimal.ZERO;

            for (AccountRepo.StoredHolding stored : accounts.holdings(latest.id())) {
                ValuedHolding holding = value(stored, account.name());
                holdings.add(holding);
                accountTotal = accountTotal.add(holding.valueNok());
                if (holding.costBasisNok() != null) {
                    accountCost = accountCost.add(holding.costBasisNok());
                    accountMeasured = accountMeasured.add(holding.valueNok());
                }
                // Practice money is shown but never counted, so it is excluded
                // from every aggregate - not just the headline figure, or the
                // live/as-of percentages would still be computed against it.
                if (!account.simulated() && holding.live()) {
                    live = live.add(holding.valueNok());
                }
            }

            if (account.simulated()) {
                simulated = simulated.add(accountTotal);
            } else {
                total = total.add(accountTotal);
                costBasis = costBasis.add(accountCost);
                measured = measured.add(accountMeasured);
                if (oldest == null || latest.asOf().isBefore(oldest)) {
                    oldest = latest.asOf();
                }
                allHoldings.addAll(holdings);
            }
            valued.add(new AccountValuation(account.id(), account.name(), account.broker(),
                    latest.asOf(), money(accountTotal), holdings.size(), account.simulated(), holdings));
        }

        // Weights need the grand total, so they are filled in afterwards.
        BigDecimal grandTotal = money(total);
        List<ValuedHolding> weighted = new ArrayList<>(allHoldings.size());
        for (ValuedHolding holding : allHoldings) {
            weighted.add(new ValuedHolding(holding.symbol(), holding.name(), holding.kind(), holding.currency(),
                    holding.quantity(), holding.avgCost(), holding.price(), holding.valueNok(),
                    holding.costBasisNok(), holding.gainNok(), holding.gainPercent(),
                    percent(holding.valueNok(), grandTotal), holding.live(), holding.accountName(),
                    holding.leverage(), holding.direction()));
        }
        weighted.sort(Comparator.comparing(ValuedHolding::valueNok).reversed());

        BigDecimal liveNok = money(live);
        // Against the measured value, not the grand total: an account with no
        // cost basis at all would otherwise report its entire value as profit.
        BigDecimal gain = measured.signum() == 0 ? null : money(measured.subtract(costBasis));

        return new Totals(
                grandTotal,
                liveNok,
                money(grandTotal.subtract(liveNok)),
                percent(liveNok, grandTotal),
                gain,
                measured.signum() == 0 ? null : money(costBasis),
                money(simulated),
                oldest,
                (int) valued.stream().filter(a -> a.holdingCount() > 0 && !a.simulated()).count(),
                weighted.size(),
                valued,
                weighted,
                fx.latestRates());
    }

    /**
     * Values one holding, preferring a live price and falling back to the
     * value stored at import.
     */
    private ValuedHolding value(AccountRepo.StoredHolding stored, String accountName) {
        BigDecimal valueNok = stored.valueNok();
        BigDecimal price = null;
        boolean live = false;

        // Only price instruments whose mapping was actually confirmed. An
        // unverified guess must not be allowed to move a real number.
        if ("YAHOO".equals(stored.priceSource()) && stored.symbol() != null && stored.verified()) {
            BigDecimal livePrice = priceOf(stored.symbol());
            if (livePrice != null) {
                BigDecimal nokPrice = fx.toNok(livePrice, stored.currency());
                if (nokPrice != null) {
                    price = livePrice;
                    valueNok = money(nokPrice.multiply(stored.quantity()));
                    live = true;
                }
            }
        }

        BigDecimal costBasis = stored.avgCost() == null
                ? null
                : fx.toNok(money(stored.avgCost().multiply(stored.quantity())), stored.currency());
        BigDecimal gain = costBasis == null ? null : money(valueNok.subtract(costBasis));

        return new ValuedHolding(
                stored.symbol(),
                stored.name(),
                stored.kind(),
                stored.currency(),
                stored.quantity().stripTrailingZeros(),
                stored.avgCost(),
                price,
                money(valueNok),
                costBasis,
                gain,
                gain == null ? null : percent(gain, costBasis),
                BigDecimal.ZERO,
                live,
                accountName,
                stored.leverage(),
                stored.direction());
    }

    /**
     * A live price, cached for a minute.
     *
     * <p>A portfolio of forty holdings refreshed on every page view would be
     * forty requests to an unofficial endpoint; the cache keeps that to forty
     * per minute at worst, and usually zero.
     */
    private BigDecimal priceOf(String symbol) {
        return priceCache.get(symbol, Duration.ofMinutes(1), key ->
                yahoo.quote(key)
                        .map(quote -> BigDecimal.valueOf(quote.price()))
                        .orElse(null));
    }

    /** Combined value per snapshot date, for a history chart. */
    public List<Map<String, Object>> history() {
        List<Map<String, Object>> points = new ArrayList<>();
        for (Object[] row : accounts.valueHistory()) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", row[0].toString());
            point.put("value", row[1]);
            points.add(point);
        }
        return points;
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 2, RoundingMode.HALF_UP);
    }
}
