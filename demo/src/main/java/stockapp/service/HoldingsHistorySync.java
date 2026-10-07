package stockapp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import stockapp.repo.AccountRepo;
import stockapp.repo.ClosingPriceRepo;
import stockapp.yahoo.YahooClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the closes and exchange rates that the holdings value history is built
 * from up to date in the database.
 *
 * <p>The history itself is computed from the database only, so the page never
 * waits on Yahoo or Norges Bank. This class does the fetching, in the
 * background: at startup, after every import or eToro sync, and once a day.
 * Each run fetches only what is missing, plus the last few stored days again.
 */
public final class HoldingsHistorySync {

    private static final Logger log = LoggerFactory.getLogger(HoldingsHistorySync.class);

    /**
     * Days before the first import that are fetched too. An import dated on a
     * weekend or holiday then still has an earlier close and rate to use.
     */
    static final int LEAD_DAYS = 7;

    /**
     * Stored days fetched again on every run. A close taken while a session
     * was open was a live price; the next run replaces it with the real one.
     */
    private static final int TOP_UP_DAYS = 5;

    /**
     * How far the first stored day may sit after the need before the gap is
     * fetched. The need often falls on a weekend or holiday, and asking again
     * every run would never get a closer answer.
     */
    private static final int GAP_TOLERANCE_DAYS = 4;

    /** A range of days to fetch, inclusive at both ends. */
    record Window(LocalDate from, LocalDate to) {
    }

    private final AccountRepo accounts;
    private final ClosingPriceRepo closes;
    private final YahooClient yahoo;
    private final FxService fx;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "history-sync");
        thread.setDaemon(true);
        return thread;
    });
    /** Guards against an import and the daily job starting two runs at once. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public HoldingsHistorySync(AccountRepo accounts, ClosingPriceRepo closes, YahooClient yahoo, FxService fx) {
        this.accounts = accounts;
        this.closes = closes;
        this.yahoo = yahoo;
        this.fx = fx;
    }

    /** Starts {@link #refresh()} on a background thread, unless a run is already going. */
    public void refreshInBackground() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker.submit(() -> {
            try {
                refresh();
            } catch (RuntimeException e) {
                log.warn("History refresh failed: {}", e.getMessage());
            } finally {
                running.set(false);
            }
        });
    }

    /**
     * Fetches every missing close for the priced holdings of real accounts, and
     * every missing rate for their currencies, from the first import on.
     */
    public void refresh() {
        LocalDate today = LocalDate.now();
        Map<String, LocalDate> symbols = new TreeMap<>();
        Map<String, LocalDate> currencies = new TreeMap<>();
        for (Valuation.AccountHistory history : load(accounts)) {
            if (history.account().simulated()) {
                continue;
            }
            for (Valuation.DatedHoldings snapshot : history.snapshots()) {
                LocalDate need = snapshot.asOf().minusDays(LEAD_DAYS);
                for (AccountRepo.StoredHolding holding : snapshot.holdings()) {
                    if (!Valuation.isPriceable(holding)) {
                        continue;
                    }
                    symbols.merge(holding.symbol(), need, HoldingsHistorySync::earlier);
                    String currency = holding.currency() == null ? "" : holding.currency().toUpperCase(Locale.ROOT);
                    if (!currency.isBlank() && !currency.equals("NOK")) {
                        currencies.merge(currency, need, HoldingsHistorySync::earlier);
                    }
                }
            }
        }

        int closesFetched = 0;
        for (Map.Entry<String, LocalDate> symbol : symbols.entrySet()) {
            ClosingPriceRepo.StoredRange stored = closes.storedRange(symbol.getKey()).orElse(null);
            for (Window window : missing(symbol.getValue(), stored, today)) {
                Map<LocalDate, BigDecimal> series = new TreeMap<>();
                yahoo.dailyCloses(symbol.getKey(), window.from(), window.to())
                        .forEach((day, close) -> series.put(day, BigDecimal.valueOf(close)));
                closes.save(symbol.getKey(), series);
                closesFetched += series.size();
            }
        }

        int ratesFetched = 0;
        if (!currencies.isEmpty()) {
            LocalDate earliest = currencies.values().stream().min(LocalDate::compareTo).orElseThrow();
            Map<String, NavigableMap<LocalDate, BigDecimal>> storedRates = fx.rateHistory(earliest);
            for (Map.Entry<String, LocalDate> currency : currencies.entrySet()) {
                NavigableMap<LocalDate, BigDecimal> series = storedRates.get(currency.getKey());
                ClosingPriceRepo.StoredRange stored = series == null || series.isEmpty()
                        ? null
                        : new ClosingPriceRepo.StoredRange(series.firstKey(), series.lastKey());
                for (Window window : missing(currency.getValue(), stored, today)) {
                    fx.fetchRateHistory(List.of(currency.getKey()), window.from(), window.to());
                    ratesFetched++;
                }
            }
        }
        log.info("History refresh: {} closes for {} symbols, {} rate requests for {} currencies",
                closesFetched, symbols.size(), ratesFetched, currencies.size());
    }

    /**
     * Every account with all its snapshots and their holdings - what both this
     * class and the history computation start from.
     */
    static List<Valuation.AccountHistory> load(AccountRepo accounts) {
        List<Valuation.AccountHistory> histories = new ArrayList<>();
        for (AccountRepo.Account account : accounts.listAccounts()) {
            List<Valuation.DatedHoldings> snapshots = new ArrayList<>();
            for (AccountRepo.Snapshot snapshot : accounts.snapshots(account.id())) {
                snapshots.add(new Valuation.DatedHoldings(snapshot.asOf(), accounts.holdings(snapshot.id())));
            }
            histories.add(new Valuation.AccountHistory(account, snapshots));
        }
        return histories;
    }

    /**
     * The ranges still to fetch for one series.
     *
     * @param need   the first day wanted
     * @param stored what is already stored, or null for nothing
     */
    static List<Window> missing(LocalDate need, ClosingPriceRepo.StoredRange stored, LocalDate today) {
        if (need.isAfter(today)) {
            return List.of();
        }
        if (stored == null) {
            return List.of(new Window(need, today));
        }
        List<Window> windows = new ArrayList<>(2);
        if (stored.first().isAfter(need.plusDays(GAP_TOLERANCE_DAYS))) {
            windows.add(new Window(need, stored.first().minusDays(1)));
        }
        LocalDate topUp = earlier(stored.last().minusDays(TOP_UP_DAYS), today);
        windows.add(new Window(topUp.isBefore(need) ? need : topUp, today));
        return windows;
    }

    private static LocalDate earlier(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
