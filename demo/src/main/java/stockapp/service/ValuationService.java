package stockapp.service;

import stockapp.repo.AccountRepo;
import stockapp.repo.ClosingPriceRepo;
import stockapp.yahoo.YahooClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Values every account in NOK, without ever making a page wait on the network.
 *
 * <p>The sums live in {@link Valuation}. This class feeds it the stored
 * holdings and whatever prices are cached, and keeps those prices fresh in the
 * background.
 */
public final class ValuationService {

    /**
     * How long a price is treated as current. Past this a refresh is started,
     * but the stale price is still served - see {@link #valueEverything()}.
     */
    private static final Duration PRICE_TTL = Duration.ofMinutes(1);

    private final AccountRepo accounts;
    private final ClosingPriceRepo closingPrices;
    private final YahooClient yahoo;
    private final FxService fx;
    private final Cache<String, YahooClient.Quote> priceCache = new Cache<>();

    /**
     * One thread to run a refresh and a small pool to fetch within it, kept
     * apart on purpose: a coordinator waiting on tasks in its own pool can
     * starve them. Both are daemons, so neither holds the JVM open.
     */
    private final ExecutorService refreshCoordinator = Executors.newSingleThreadExecutor(daemon("price-refresh"));
    private final ExecutorService pricePool = Executors.newFixedThreadPool(8, daemon("price-fetch"));

    /** Guards against a dozen page loads starting a dozen refreshes. */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    /** When the last refresh finished, for the "prices from …" label. */
    private volatile Instant pricesAsOf;

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    public ValuationService(AccountRepo accounts, ClosingPriceRepo closingPrices, YahooClient yahoo,
                            FxService fx) {
        this.accounts = accounts;
        this.closingPrices = closingPrices;
        this.yahoo = yahoo;
        this.fx = fx;
    }

    /**
     * Values every account against its most recent snapshot.
     *
     * <p>This never waits on the network. Prices come from the cache whatever
     * their age, and anything past its time-to-live is queued for a background
     * refresh that a later call will pick up. Fetching 38 symbols one at a time
     * took three and a half seconds, which was paid on every visit where the
     * cache had gone cold - so the page was fast only if you had just seen it.
     */
    public Valuation.Totals valueEverything() {
        List<Valuation.AccountInput> inputs = new ArrayList<>();
        for (AccountRepo.Account account : accounts.listAccounts()) {
            Optional<AccountRepo.Snapshot> snapshot = accounts.latestSnapshot(account.id());
            inputs.add(new Valuation.AccountInput(account, snapshot.orElse(null),
                    snapshot.map(s -> accounts.holdings(s.id())).orElse(List.of())));
        }

        // Fresh or not, whatever is cached is what gets served; a price a few
        // minutes old is a far better answer than a spinner. Anything past its
        // TTL is noted so a refresh can be started afterwards.
        Set<String> stalePrices = new LinkedHashSet<>();
        Valuation.Prices cached = symbol -> {
            YahooClient.Quote quote = priceCache.peek(symbol);
            if (quote == null) {
                stalePrices.add(symbol);
                quote = priceCache.peekStale(symbol);
            }
            return quote;
        };
        Valuation.Totals totals = Valuation.compute(inputs, cached, fx::toNok, fx.latestRates(), pricesAsOf);

        // Start the refresh only once the response is fully built, so nothing
        // above it can ever be waiting on a network call.
        boolean refreshStarted = startRefresh(stalePrices);
        return totals.withPricesRefreshing(refreshStarted || refreshing.get());
    }

    /**
     * Fetches the given symbols in the background, in parallel.
     *
     * @return true if this call started a refresh, false if nothing needed one
     *         or one was already running
     *
     * <p>Parallel because sequential was the whole problem: 38 symbols at
     * roughly 90ms each is three and a half seconds. Fetching them at once
     * turns that into about the slowest single request, and since it no longer
     * blocks a response, its only cost is arriving slightly later.
     *
     * <p>A failed symbol is simply not written, so the previous price stays and
     * the next pass tries again. There is no retry here on purpose - a refresh
     * runs often enough that retrying inside one only doubles the load on an
     * upstream that is already unhappy.
     */
    private boolean startRefresh(Set<String> symbols) {
        if (symbols.isEmpty() || !refreshing.compareAndSet(false, true)) {
            return false;
        }
        List<String> wanted = List.copyOf(symbols);
        refreshCoordinator.submit(() -> {
            try {
                List<Future<?>> pending = new ArrayList<>(wanted.size());
                for (String symbol : wanted) {
                    pending.add(pricePool.submit(() -> {
                        YahooClient.Quote fetched = yahoo.quote(symbol).orElse(null);
                        if (fetched != null) {
                            priceCache.put(symbol, fetched, PRICE_TTL);
                        }
                    }));
                }
                for (Future<?> task : pending) {
                    try {
                        task.get();
                    } catch (Exception e) {
                        // One symbol failing must not abandon the rest.
                    }
                }
                pricesAsOf = Instant.now();
            } finally {
                // Without this a single thrown exception would wedge the flag
                // and no refresh would ever run again.
                refreshing.set(false);
            }
        });
        return true;
    }

    /**
     * The combined value of every real account on each day since the first
     * import, for the history chart. Reads only the database: the closes and
     * rates it uses are fetched in the background by {@link HoldingsHistorySync}.
     */
    public List<AccountRepo.ValuePoint> history() {
        List<Valuation.AccountHistory> histories = HoldingsHistorySync.load(accounts);
        Set<String> symbols = new LinkedHashSet<>();
        LocalDate first = null;
        for (Valuation.AccountHistory history : histories) {
            for (Valuation.DatedHoldings snapshot : history.snapshots()) {
                if (first == null || snapshot.asOf().isBefore(first)) {
                    first = snapshot.asOf();
                }
                for (AccountRepo.StoredHolding holding : snapshot.holdings()) {
                    if (Valuation.isPriceable(holding)) {
                        symbols.add(holding.symbol());
                    }
                }
            }
        }
        if (first == null) {
            return List.of();
        }
        LocalDate from = first.minusDays(HoldingsHistorySync.LEAD_DAYS);
        return Valuation.history(histories, closingPrices.closes(symbols, from), fx.rateHistory(from),
                LocalDate.now());
    }
}
