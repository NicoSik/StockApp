package stockapp.service;

import stockapp.repo.AccountRepo;
import stockapp.yahoo.YahooClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static stockapp.model.Money.money;
import static stockapp.model.Money.percent;

/**
 * The arithmetic behind the holdings page, with no I/O, so it can be tested.
 *
 * <p>Two valuation paths, and the difference is reported rather than hidden:
 *
 * <ul>
 *   <li><b>Live</b> - the instrument has a verified symbol and a price, so it
 *       is valued at that price converted to NOK. Shares, ETFs, and mutual
 *       funds once they have been identified.</li>
 *   <li><b>As of import</b> - no verified symbol, or no price yet. The value
 *       the broker reported is used, and the date it came from is carried
 *       alongside it.</li>
 * </ul>
 *
 * <p>Presenting an old broker figure as though it were current would be a
 * small lie that compounds. The split is surfaced instead, so a total reads
 * "412 500 kr - 99.9% priced live, 600 kr as of 14 Aug".
 *
 * <p>Bank balances are cash, not investments. They count towards the total -
 * it is a net worth - but every figure about how the investments are doing is
 * taken over the investments alone: weights, the live share and the table.
 * Otherwise a large balance shrinks every weight and reads as unpriced money.
 *
 * <p>{@link ValuationService} supplies the stored holdings and cached prices;
 * this class only does the sums.
 */
public final class Valuation {

    private Valuation() {
    }

    /** The cached quote for a symbol, however old; null when there is none. */
    @FunctionalInterface
    interface Prices {
        YahooClient.Quote quote(String symbol);
    }

    /** {@code amount} in {@code currency} as NOK; null when no rate is known. */
    @FunctionalInterface
    interface Fx {
        BigDecimal toNok(BigDecimal amount, String currency);
    }

    /**
     * One account as stored.
     *
     * @param snapshot its most recent snapshot, or null if it was never imported
     * @param holdings that snapshot's holdings; empty when there is none
     */
    record AccountInput(AccountRepo.Account account,
                        AccountRepo.Snapshot snapshot,
                        List<AccountRepo.StoredHolding> holdings) {
    }

    /** One account with every snapshot it has, in any order. */
    record AccountHistory(AccountRepo.Account account, List<DatedHoldings> snapshots) {
    }

    /** The holdings of one snapshot, and the date it describes. */
    record DatedHoldings(LocalDate asOf, List<AccountRepo.StoredHolding> holdings) {
    }

    /**
     * @param dayChangeNok what this holding has made or lost today, in NOK.
     *                     Null wherever there is no live price to compare a
     *                     previous close against - a broker-supplied value has
     *                     no intraday history behind it.
     * @param leverage  above 1 for a CFD, null for an ordinary holding
     * @param direction LONG or SHORT where the distinction exists
     * @param instrumentId the stored instrument, so a holding can be re-matched
     */
    public record ValuedHolding(int instrumentId,
                                String symbol,
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
                                BigDecimal dayChangeNok,
                                BigDecimal dayChangePercent,
                                boolean live,
                                String accountName,
                                BigDecimal leverage,
                                String direction) {

        /** The same holding with its share of the portfolio filled in. */
        ValuedHolding withWeight(BigDecimal weight) {
            return new ValuedHolding(instrumentId, symbol, name, kind, currency, quantity, avgCost, price, valueNok,
                    costBasisNok, gainNok, gainPercent, weight, dayChangeNok, dayChangePercent,
                    live, accountName, leverage, direction);
        }
    }

    /**
     * @param costBasisNok      what the holdings cost, where that is known
     * @param gainNok           value minus cost over the measurable part only,
     *                          which is why {@link #valueNok} minus
     *                          {@link #costBasisNok} need not equal it
     * @param costBasisReported true when the broker stated the cost basis for
     *                          the account as a whole instead of per holding.
     *                          DNB's report does exactly that, so its gain is
     *                          real but cannot be attributed to a single row.
     */
    public record AccountValuation(int id,
                                   String name,
                                   String broker,
                                   LocalDate asOf,
                                   BigDecimal valueNok,
                                   BigDecimal costBasisNok,
                                   BigDecimal gainNok,
                                   BigDecimal gainPercent,
                                   boolean costBasisReported,
                                   BigDecimal dayChangeNok,
                                   int holdingCount,
                                   boolean simulated,
                                   List<ValuedHolding> holdings) {

        AccountValuation withHoldings(List<ValuedHolding> holdings) {
            return new AccountValuation(id, name, broker, asOf, valueNok, costBasisNok, gainNok,
                    gainPercent, costBasisReported, dayChangeNok, holdingCount, simulated, holdings);
        }
    }

    /**
     * @param totalNok     real money only; simulated accounts are excluded
     * @param investmentsNok the total without bank balances
     * @param cashNok      bank balances; {@code totalNok = investmentsNok + cashNok}
     * @param liveNok      priced live, out of {@code investmentsNok}
     * @param asOfNok      investments carried at the broker's value
     * @param holdings     the investments, largest first; bank balances are in {@code cash}
     * @param simulatedNok practice money, reported separately so it can be shown
     *                     without ever being added to a net worth
     * @param pricesRefreshing a refresh is running now, so these figures are
     *                         the previous ones and a later call will differ
     * @param pricesAsOf   when the served prices were fetched, null before the
     *                     first refresh has finished
     */
    public record Totals(BigDecimal totalNok,
                         BigDecimal investmentsNok,
                         BigDecimal cashNok,
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
                         List<ValuedHolding> cash,
                         Map<String, BigDecimal> fxRates,
                         BigDecimal dayChangeNok,
                         BigDecimal dayChangeBaseNok,
                         boolean pricesRefreshing,
                         Instant pricesAsOf) {

        Totals withPricesRefreshing(boolean refreshing) {
            return new Totals(totalNok, investmentsNok, cashNok, liveNok, asOfNok, livePercent, gainNok,
                    costBasisNok, simulatedNok, oldestAsOf, accountCount, holdingCount, accounts, holdings, cash,
                    fxRates,
                    dayChangeNok, dayChangeBaseNok, refreshing, pricesAsOf);
        }
    }

    /**
     * One account's valuation plus the unrounded sums the grand total is built
     * from. Kept apart from {@link AccountValuation} because these are working
     * figures, not something the API reports.
     *
     * @param measured      value of the rows whose cost is known
     * @param dayChangeBase value of the rows that have a previous close
     */
    private record AccountTally(AccountValuation valuation,
                                BigDecimal total,
                                BigDecimal cost,
                                BigDecimal measured,
                                BigDecimal live,
                                BigDecimal dayChange,
                                BigDecimal dayChangeBase) {
    }

    /**
     * Values every account and combines them.
     *
     * <p>{@code pricesRefreshing} is always false here; only the caller knows
     * whether its refresh started - see {@link Totals#withPricesRefreshing}.
     */
    static Totals compute(List<AccountInput> inputs, Prices prices, Fx fx,
                          Map<String, BigDecimal> fxRates, Instant pricesAsOf) {
        List<AccountTally> tallies = new ArrayList<>(inputs.size());
        for (AccountInput input : inputs) {
            tallies.add(valueAccount(input, prices, fx));
        }

        BigDecimal total = BigDecimal.ZERO;
        BigDecimal cash = BigDecimal.ZERO;
        BigDecimal live = BigDecimal.ZERO;
        BigDecimal costBasis = BigDecimal.ZERO;
        BigDecimal measured = BigDecimal.ZERO;
        BigDecimal simulated = BigDecimal.ZERO;
        // Today's move, and the value it was measured over - the two must be
        // reported together or a percentage is against the wrong denominator.
        BigDecimal dayChange = BigDecimal.ZERO;
        BigDecimal dayChangeBase = BigDecimal.ZERO;
        LocalDate oldest = null;

        for (AccountTally tally : tallies) {
            AccountValuation account = tally.valuation();
            if (account.asOf() == null) {
                continue;  // never imported, so there is nothing to add
            }
            // Practice money is shown but never counted, so it is excluded
            // from every aggregate - not just the headline figure, or the
            // live/as-of percentages would still be computed against it.
            if (account.simulated()) {
                simulated = simulated.add(tally.total());
                continue;
            }
            total = total.add(tally.total());
            if (isCash(account)) {
                cash = cash.add(tally.total());
            }
            costBasis = costBasis.add(tally.cost());
            measured = measured.add(tally.measured());
            live = live.add(tally.live());
            dayChange = dayChange.add(tally.dayChange());
            dayChangeBase = dayChangeBase.add(tally.dayChangeBase());
            if (oldest == null || account.asOf().isBefore(oldest)) {
                oldest = account.asOf();
            }
        }

        // Weights need the investments total, so they are filled in afterwards -
        // and onto each account's own copy too, so a caller showing one
        // account's holdings gets the same rows as the combined table rather
        // than a parallel set that silently reads 0%.
        BigDecimal grandTotal = money(total);
        BigDecimal investments = money(total.subtract(cash));
        List<AccountValuation> accounts = new ArrayList<>(tallies.size());
        for (AccountTally tally : tallies) {
            AccountValuation account = tally.valuation();
            List<ValuedHolding> weighted = new ArrayList<>(account.holdings().size());
            for (ValuedHolding holding : account.holdings()) {
                // Practice money is not part of the total, and a bank balance
                // is not an investment, so neither has a share of the
                // investments. Null reads as "-"; zero would read as "nothing".
                weighted.add(holding.withWeight(account.simulated() || isCash(account)
                        ? null : percent(holding.valueNok(), investments)));
            }
            accounts.add(account.withHoldings(weighted));
        }

        // The combined table is real investments only; balances go in their own list.
        List<ValuedHolding> holdings = accounts.stream()
                .filter(account -> !account.simulated() && !isCash(account))
                .flatMap(account -> account.holdings().stream())
                .sorted(Comparator.comparing(ValuedHolding::valueNok).reversed())
                .collect(Collectors.toCollection(ArrayList::new));
        List<ValuedHolding> balances = accounts.stream()
                .filter(account -> !account.simulated() && isCash(account))
                .flatMap(account -> account.holdings().stream())
                .sorted(Comparator.comparing(ValuedHolding::valueNok).reversed())
                .collect(Collectors.toCollection(ArrayList::new));

        // A balance is never live, so live money is all investments already.
        BigDecimal liveNok = money(live);
        // Against the measured value, not the grand total: an account with no
        // cost basis at all would otherwise report its entire value as profit.
        BigDecimal gain = measured.signum() == 0 ? null : money(measured.subtract(costBasis));

        return new Totals(
                grandTotal,
                investments,
                money(cash),
                liveNok,
                money(investments.subtract(liveNok)),
                percent(liveNok, investments),
                gain,
                measured.signum() == 0 ? null : money(costBasis),
                money(simulated),
                oldest,
                (int) accounts.stream().filter(a -> a.holdingCount() > 0 && !a.simulated()).count(),
                holdings.size(),
                accounts,
                holdings,
                balances,
                fxRates,
                dayChangeBase.signum() == 0 ? null : money(dayChange),
                dayChangeBase.signum() == 0 ? null : money(dayChangeBase),
                false,
                pricesAsOf);
    }

    /**
     * The combined value of every real account on each day, from the first
     * import to {@code today}.
     *
     * <p>Each day takes the snapshot in force for each account - its latest one
     * on or before that day - and values every holding exactly as {@link #value}
     * does live, but at that day's close and exchange rate. A missing close or
     * rate carries the last one forward; before the first close, and for
     * anything without a price, the value the broker reported stands.
     *
     * <p>The days are every import date, every date with a close, and today. So
     * weekends and holidays drop out, and a portfolio of unpriced funds still
     * reaches today as a flat line instead of stopping at its import.
     *
     * @param closes per symbol, the closing price on each trading day
     * @param rates  per currency, NOK per one unit on each business day
     */
    static List<AccountRepo.ValuePoint> history(List<AccountHistory> accounts,
                                               Map<String, NavigableMap<LocalDate, BigDecimal>> closes,
                                               Map<String, NavigableMap<LocalDate, BigDecimal>> rates,
                                               LocalDate today) {
        List<NavigableMap<LocalDate, List<AccountRepo.StoredHolding>>> timelines = new ArrayList<>();
        TreeSet<LocalDate> days = new TreeSet<>();
        for (AccountHistory account : accounts) {
            // Practice money has no business in a net-worth history.
            if (account.account().simulated() || account.snapshots().isEmpty()) {
                continue;
            }
            NavigableMap<LocalDate, List<AccountRepo.StoredHolding>> timeline = new TreeMap<>();
            for (DatedHoldings snapshot : account.snapshots()) {
                timeline.put(snapshot.asOf(), snapshot.holdings());
            }
            timelines.add(timeline);
            days.addAll(timeline.keySet());
        }
        if (days.isEmpty()) {
            return List.of();
        }

        LocalDate first = days.first();
        if (!today.isBefore(first)) {
            for (NavigableMap<LocalDate, BigDecimal> series : closes.values()) {
                days.addAll(series.subMap(first, true, today, true).keySet());
            }
            days.add(today);
        }

        List<AccountRepo.ValuePoint> points = new ArrayList<>(days.size());
        for (LocalDate day : days) {
            Prices prices = symbol -> {
                BigDecimal close = onOrBefore(closes.get(symbol), day);
                return close == null ? null : new YahooClient.Quote(symbol, close.doubleValue(), "", "", 0L, null);
            };
            Fx fx = (amount, currency) -> {
                if (currency == null || currency.isBlank() || currency.equalsIgnoreCase("NOK")) {
                    return amount;
                }
                BigDecimal rate = onOrBefore(rates.get(currency.toUpperCase(Locale.ROOT)), day);
                return rate == null ? null : money(amount.multiply(rate));
            };

            BigDecimal total = BigDecimal.ZERO;
            for (NavigableMap<LocalDate, List<AccountRepo.StoredHolding>> timeline : timelines) {
                Map.Entry<LocalDate, List<AccountRepo.StoredHolding>> inForce = timeline.floorEntry(day);
                if (inForce == null) {
                    continue;  // not imported yet on this day
                }
                for (AccountRepo.StoredHolding holding : inForce.getValue()) {
                    total = total.add(value(holding, null, prices, fx).valueNok());
                }
            }
            points.add(new AccountRepo.ValuePoint(day, money(total)));
        }
        return points;
    }

    /**
     * Whether a holding is valued from a market price at all. Only instruments
     * whose mapping was actually confirmed: an unverified guess must not be
     * allowed to move a real number.
     */
    static boolean isPriceable(AccountRepo.StoredHolding holding) {
        return "YAHOO".equals(holding.priceSource()) && holding.symbol() != null && holding.verified();
    }

    /** Whether an account holds bank balances rather than investments. */
    static boolean isCash(AccountValuation account) {
        return BankSyncService.BROKER.equals(account.broker());
    }

    /** The last value on or before {@code day}, or null when there is none. */
    private static BigDecimal onOrBefore(NavigableMap<LocalDate, BigDecimal> series, LocalDate day) {
        if (series == null) {
            return null;
        }
        Map.Entry<LocalDate, BigDecimal> entry = series.floorEntry(day);
        return entry == null ? null : entry.getValue();
    }

    private static AccountTally valueAccount(AccountInput input, Prices prices, Fx fx) {
        AccountRepo.Account account = input.account();
        AccountRepo.Snapshot latest = input.snapshot();
        if (latest == null) {
            AccountValuation empty = new AccountValuation(account.id(), account.name(), account.broker(),
                    null, BigDecimal.ZERO, null, null, null, false, null, 0, account.simulated(), List.of());
            return new AccountTally(empty, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        List<ValuedHolding> holdings = new ArrayList<>(input.holdings().size());
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        // Value of the rows whose cost is known. Gain is measured against this,
        // never against the account total, so a holding with no cost basis
        // cannot masquerade as pure profit.
        BigDecimal measured = BigDecimal.ZERO;
        BigDecimal live = BigDecimal.ZERO;
        // Only the rows that actually have a previous close contribute; the
        // rest are absent from both sides rather than counted as flat.
        BigDecimal dayChange = null;
        BigDecimal dayChangeBase = BigDecimal.ZERO;

        for (AccountRepo.StoredHolding stored : input.holdings()) {
            ValuedHolding holding = value(stored, account.name(), prices, fx);
            holdings.add(holding);
            total = total.add(holding.valueNok());
            if (holding.costBasisNok() != null) {
                cost = cost.add(holding.costBasisNok());
                measured = measured.add(holding.valueNok());
            }
            if (holding.live()) {
                live = live.add(holding.valueNok());
            }
            if (holding.dayChangeNok() != null) {
                dayChange = (dayChange == null ? BigDecimal.ZERO : dayChange).add(holding.dayChangeNok());
                dayChangeBase = dayChangeBase.add(holding.valueNok());
            }
        }

        // DNB states Kostpris for the portfolio and nothing per row, so
        // without this the account showed a dash where it has a real gain.
        boolean reported = false;
        if (measured.signum() == 0 && latest.reportedCostBasisNok() != null
                && latest.reportedCostBasisNok().signum() != 0) {
            cost = latest.reportedCostBasisNok();
            measured = total;
            reported = true;
        }
        BigDecimal gain = measured.signum() == 0 ? null : money(measured.subtract(cost));

        AccountValuation valuation = new AccountValuation(account.id(), account.name(), account.broker(),
                latest.asOf(), money(total),
                gain == null ? null : money(cost),
                gain,
                gain == null ? null : percent(gain, cost),
                reported, dayChange, holdings.size(), account.simulated(), holdings);
        return new AccountTally(valuation, total, cost, measured, live,
                dayChange == null ? BigDecimal.ZERO : dayChange, dayChangeBase);
    }

    /**
     * Values one holding, preferring a live price and falling back to the
     * value stored at import.
     */
    static ValuedHolding value(AccountRepo.StoredHolding stored, String accountName, Prices prices, Fx fx) {
        BigDecimal valueNok = stored.valueNok();
        BigDecimal price = null;
        BigDecimal dayChange = null;
        BigDecimal dayChangePercent = null;
        boolean live = false;
        if (isPriceable(stored)) {
            YahooClient.Quote quote = prices.quote(stored.symbol());
            if (quote != null) {
                BigDecimal livePrice = BigDecimal.valueOf(quote.price());
                BigDecimal nokPrice = fx.toNok(livePrice, stored.currency());
                if (nokPrice != null) {
                    price = livePrice;
                    valueNok = money(nokPrice.multiply(stored.quantity()));
                    live = true;

                    // Today's move, but only where the feed actually supplies a
                    // previous close. A missing one is left null rather than
                    // treated as no movement, which would read as a flat day.
                    if (quote.previousClose() != null && quote.previousClose() > 0) {
                        BigDecimal previous = fx.toNok(
                                BigDecimal.valueOf(quote.previousClose()), stored.currency());
                        if (previous != null) {
                            BigDecimal was = money(previous.multiply(stored.quantity()));
                            dayChange = money(valueNok.subtract(was));
                            dayChangePercent = percent(dayChange, was);
                        }
                    }
                }
            }
        }

        BigDecimal costBasis = stored.avgCost() == null
                ? null
                : fx.toNok(money(stored.avgCost().multiply(stored.quantity())), stored.currency());
        BigDecimal gain = costBasis == null ? null : money(valueNok.subtract(costBasis));

        return new ValuedHolding(
                stored.instrumentId(),
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
                dayChange,
                dayChangePercent,
                live,
                accountName,
                stored.leverage(),
                stored.direction());
    }
}
