package stockapp.service;

import stockapp.model.Stock;
import stockapp.repo.StockRepo;
import stockapp.repo.WatchlistRepo;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background jobs.
 *
 * <p>Three of them:
 * <ul>
 *   <li><b>alerts</b> - every minute, so a crossed threshold is noticed while
 *       the user is looking at something else</li>
 *   <li><b>end-of-day bars</b> - once daily after the close, keeping stored
 *       history current for the portfolio chart and the offline fallback</li>
 *   <li><b>asset sync</b> - once daily, picking up new listings</li>
 * </ul>
 *
 * <p>Every task body is wrapped in a catch-all. A scheduled task that throws is
 * silently cancelled for the rest of the process lifetime, which is a
 * particularly annoying failure mode to debug.
 */
public final class Scheduler implements AutoCloseable {

    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    /** 20 minutes after the close, late enough for the closing print to settle. */
    private static final LocalTime END_OF_DAY_JOB = LocalTime.of(16, 20);
    private static final LocalTime ASSET_SYNC_JOB = LocalTime.of(5, 30);

    private final ScheduledExecutorService executor;
    private final AlertService alerts;
    private final Importer importer;
    private final StockRepo stocks;
    private final WatchlistRepo watchlists;

    public Scheduler(AlertService alerts, Importer importer, StockRepo stocks, WatchlistRepo watchlists) {
        this.alerts = alerts;
        this.importer = importer;
        this.stocks = stocks;
        this.watchlists = watchlists;
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
        scheduleDaily("asset sync", ASSET_SYNC_JOB, importer::syncAssets);

        System.out.println("[scheduler] alerts every 60s; daily jobs at "
                + END_OF_DAY_JOB + " and " + ASSET_SYNC_JOB + " " + MARKET_ZONE.getId());
    }

    private void evaluateAlerts() {
        alerts.evaluate();
    }

    /** Tops up stored daily bars for everything the user watches or holds. */
    private void refreshWatchedHistory() {
        List<String> symbols = watchlists.allSymbols();
        LocalDate from = LocalDate.now().minusDays(10);
        int updated = 0;
        for (String symbol : symbols) {
            Stock stock = stocks.findBySymbol(symbol).orElse(null);
            if (stock != null && importer.backfillDaily(stock, from) > 0) {
                updated++;
            }
        }
        System.out.printf("[scheduler] end-of-day refresh touched %d of %d watched symbols%n",
                updated, symbols.size());
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
                System.err.println("[scheduler] job \"" + name + "\" failed: " + e.getMessage());
            }
        };
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
