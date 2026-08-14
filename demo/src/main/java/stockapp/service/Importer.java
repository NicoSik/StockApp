package stockapp.service;

import stockapp.alpaca.AlpacaClient;
import stockapp.alpaca.AlpacaException;
import stockapp.model.Candle;
import stockapp.model.Stock;
import stockapp.repo.StockRepo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moves data from Alpaca into PostgreSQL: the tradable asset universe, and
 * daily bar history for symbols the user actually cares about.
 *
 * <p>Only daily bars are stored. Intraday series are fetched live and cached in
 * memory instead, because they are large, they go stale in seconds, and nothing
 * in the app needs them after the chart is drawn.
 */
public final class Importer {

    private final AlpacaClient alpaca;
    private final StockRepo stocks;

    /**
     * Remembers which symbols already had their history checked this run, so a
     * portfolio page with ten holdings does not re-verify coverage ten times.
     */
    private final Map<String, LocalDate> coverageChecked = new ConcurrentHashMap<>();

    public Importer(AlpacaClient alpaca, StockRepo stocks) {
        this.alpaca = alpaca;
        this.stocks = stocks;
    }

    /**
     * Refreshes the {@code stock} table from Alpaca's asset list.
     *
     * <p>This downloads tens of megabytes and takes a while, so it is off by
     * default at startup ({@code SYNC_ASSETS_ON_START}) and normally runs on the
     * daily schedule instead.
     */
    public int syncAssets() {
        long startedAt = System.currentTimeMillis();
        List<AlpacaClient.Asset> assets = alpaca.listAssets();
        int changed = stocks.upsertAssets(assets);
        System.out.printf("[import] asset sync: %d assets seen, %d rows written in %.1fs%n",
                assets.size(), changed, (System.currentTimeMillis() - startedAt) / 1000.0);
        return changed;
    }

    /**
     * Guarantees that daily bars going back to {@code from} are in the database,
     * fetching them only when what is stored does not already cover the window.
     *
     * @return true if bars were fetched
     */
    public boolean ensureDailyCoverage(Stock stock, LocalDate from) {
        LocalDate alreadyChecked = coverageChecked.get(stock.symbol());
        if (alreadyChecked != null && !alreadyChecked.isAfter(from)) {
            return false;
        }

        List<Candle> stored = stocks.dailyBars(stock.id(), from);
        boolean sufficient = !stored.isEmpty()
                && dateOf(stored.get(0)).minusDays(7).isBefore(from)
                && dateOf(stored.get(stored.size() - 1)).isAfter(LocalDate.now(ZoneOffset.UTC).minusDays(5));

        if (sufficient) {
            coverageChecked.put(stock.symbol(), from);
            return false;
        }

        int written = backfillDaily(stock, from);
        if (written >= 0) {
            coverageChecked.put(stock.symbol(), from);
        }
        return written > 0;
    }

    /**
     * Fetches and stores split-adjusted daily bars from {@code from} to today.
     *
     * @return rows written, or -1 when Alpaca could not be reached
     */
    public int backfillDaily(Stock stock, LocalDate from) {
        try {
            List<Candle> bars = alpaca.bars(
                    stock.symbol(),
                    "1Day",
                    from.atStartOfDay(ZoneOffset.UTC).toInstant(),
                    Instant.now(),
                    true);
            if (bars.isEmpty()) {
                return 0;
            }
            int written = stocks.saveDailyBars(stock.id(), bars);
            System.out.printf("[import] %s: stored %d daily bars from %s%n", stock.symbol(), written, from);
            return written;
        } catch (AlpacaException e) {
            System.out.println("[import] daily backfill failed for " + stock.symbol() + ": " + e.getMessage());
            return -1;
        }
    }

    private static LocalDate dateOf(Candle candle) {
        return Instant.ofEpochMilli(candle.time()).atZone(ZoneOffset.UTC).toLocalDate();
    }
}
