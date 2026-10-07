package stockapp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import stockapp.model.Stock;
import stockapp.repo.StockRepo;
import stockapp.repo.WatchlistRepo;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background jobs.
 *
 * <p>Four of them:
 * <ul>
 *   <li><b>alerts</b> - every minute, so a crossed threshold is noticed while
 *       the user is looking at something else</li>
 *   <li><b>end-of-day bars</b> - once daily after the close, keeping stored
 *       history current for the portfolio chart and the offline fallback</li>
 *   <li><b>holdings day</b> - once daily after the close: syncs eToro and the
 *       linked banks when they are configured, then fetches the closes and
 *       rates the holdings value history is missing</li>
 *   <li><b>asset sync</b> - once daily, picking up new listings</li>
 * </ul>
 *
 * <p>Every task body is wrapped in a catch-all. A scheduled task that throws is
 * silently cancelled for the rest of the process lifetime, which is a
 * particularly annoying failure mode to debug.
 */
public final class Scheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    /** 20 minutes after the close, late enough for the closing print to settle. */
    private static final LocalTime END_OF_DAY_JOB = LocalTime.of(16, 20);
    private static final LocalTime ASSET_SYNC_JOB = LocalTime.of(5, 30);

    private final ScheduledExecutorService executor;
    private final AlertService alerts;
    private final AlpacaSync alpacaSync;
    private final StockRepo stocks;
    private final WatchlistRepo watchlists;
    private final EtoroSyncService etoroSync;
    private final BankSyncService bankSync;
    private final HoldingsHistorySync historySync;

    public Scheduler(AlertService alerts, AlpacaSync alpacaSync, StockRepo stocks, WatchlistRepo watchlists,
                     EtoroSyncService etoroSync, BankSyncService bankSync, HoldingsHistorySync historySync) {
        this.alerts = alerts;
        this.alpacaSync = alpacaSync;
        this.stocks = stocks;
        this.watchlists = watchlists;
        this.etoroSync = etoroSync;
        this.bankSync = bankSync;
        this.historySync = historySync;
        this.executor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "ticker-scheduler");
            // Daemon: a background job must never keep the JVM alive on Ctrl-C.
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        executor.scheduleWithFixedDelay(
                guarded("alerts", this::evaluateAlerts), 30, 60, TimeUnit.SECONDS);

        scheduleDaily("end-of-day bars", END_OF_DAY_JOB, this::refreshWatchedHistory);
        scheduleDaily("holdings day", END_OF_DAY_JOB, this::holdingsDay);
        scheduleDaily("asset sync", ASSET_SYNC_JOB, alpacaSync::syncAssets);

        log.info("Alerts every 60s; daily jobs at {} and {} {}", END_OF_DAY_JOB, ASSET_SYNC_JOB, MARKET_ZONE.getId());
    }

    private void evaluateAlerts() {
        alerts.evaluate();
    }

    /**
     * Syncs eToro and the linked banks, so their figures are a day old at most
     * rather than as old as the last time someone pressed a button, then fills
     * in the history.
     */
    private void holdingsDay() {
        List<Runnable> syncs = new ArrayList<>();
        if (etoroSync.configured()) {
            syncs.add(() -> {
                EtoroSyncService.Result result = etoroSync.sync();
                log.info("Daily eToro sync: {} positions, {} kr", result.positions(), result.totalNok());
            });
        }
        if (bankSync.configured()) {
            syncs.add(() -> {
                for (BankSyncService.Result result : bankSync.syncAll()) {
                    log.info("Daily bank sync: {}, {} accounts, {} kr {}", result.bank(), result.accounts(),
                            result.totalNok(), result.notes());
                }
            });
        }
        runHoldingsDay(syncs, historySync::refresh);
    }

    /**
     * The holdings day's steps, in order. A failed sync is logged and the rest
     * still run: bad keys or a stall at one provider must not cost the other
     * accounts their day of history.
     */
    static void runHoldingsDay(List<Runnable> syncs, Runnable historyRefresh) {
        for (Runnable sync : syncs) {
            try {
                sync.run();
            } catch (RuntimeException e) {
                log.warn("Daily sync failed: {}", e.getMessage());
            }
        }
        historyRefresh.run();
    }

    /** Tops up stored daily bars for everything the user watches or holds. */
    private void refreshWatchedHistory() {
        List<String> symbols = watchlists.allSymbols();
        LocalDate from = LocalDate.now().minusDays(10);
        int updated = 0;
        for (String symbol : symbols) {
            Stock stock = stocks.findBySymbol(symbol).orElse(null);
            if (stock != null && alpacaSync.backfillDaily(stock, from) > 0) {
                updated++;
            }
        }
        log.info("End-of-day refresh touched {} of {} watched symbols", updated, symbols.size());
    }

    /**
     * Runs {@code task} at {@code time} in market-local time, every day.
     *
     * <p>Rescheduled after each run rather than fixed-rate, so it stays correct
     * across daylight-saving transitions instead of drifting by an hour.
     */
    private void scheduleDaily(String name, LocalTime time, Runnable task) {
        long delaySeconds = secondsUntilNext(time);
        executor.schedule(() -> {
            guarded(name, task).run();
            scheduleDaily(name, time, task);
        }, delaySeconds, TimeUnit.SECONDS);
    }

    static long secondsUntilNext(LocalTime target) {
        ZonedDateTime now = ZonedDateTime.now(MARKET_ZONE);
        ZonedDateTime next = now.with(target);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return Math.max(1, Duration.between(now, next).getSeconds());
    }

    private Runnable guarded(String name, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                // Swallow, so a transient failure does not cancel the schedule.
                log.error("Job \"{}\" failed", name, e);
            }
        };
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
