package stockapp;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import stockapp.alpaca.AlpacaClient;
import stockapp.model.Stock;
import stockapp.repo.AlertRepo;
import stockapp.repo.PortfolioRepo;
import stockapp.repo.StockRepo;
import stockapp.repo.WatchlistRepo;
import stockapp.service.AlertService;
import stockapp.service.Importer;
import stockapp.service.MarketData;
import stockapp.service.PortfolioService;
import stockapp.service.Scheduler;
import stockapp.web.Api;
import stockapp.web.GsonMapper;

import java.math.BigDecimal;
import java.util.List;

/**
 * Entry point: builds the object graph, seeds first-run data, starts the server.
 *
 * <p>Startup is ordered so that a misconfiguration is reported before anything
 * expensive happens - credentials, then the database, then the network.
 */
public final class App {

    private static final String PORTFOLIO_NAME = "Paper Portfolio";

    /** Seeded on first run so a fresh install opens on something, not a blank page. */
    private static final List<String> STARTER_SYMBOLS =
            List.of("AAPL", "MSFT", "NVDA", "TSLA", "AMZN", "GOOGL", "META", "SPY");

    public static void main(String[] args) {
        banner();
        Config.validate();
        System.out.println(Config.summary());

        Db db = new Db();
        db.migrate();

        StockRepo stocks = new StockRepo(db);
        WatchlistRepo watchlists = new WatchlistRepo(db);
        PortfolioRepo portfolios = new PortfolioRepo(db);
        AlertRepo alerts = new AlertRepo(db);

        AlpacaClient alpaca = new AlpacaClient();
        MarketData marketData = new MarketData(alpaca, stocks);
        Importer importer = new Importer(alpaca, stocks);
        PortfolioService portfolio = new PortfolioService(portfolios, stocks, marketData, importer, PORTFOLIO_NAME);
        AlertService alertService = new AlertService(alerts, marketData);

        portfolios.ensurePortfolio(PORTFOLIO_NAME, new BigDecimal(Config.PAPER_STARTING_CASH));
        seedWatchlist(stocks, watchlists);

        if (Config.SYNC_ASSETS_ON_START) {
            try {
                importer.syncAssets();
            } catch (RuntimeException e) {
                System.out.println("[startup] asset sync skipped: " + e.getMessage());
            }
        } else {
            // Locale.ROOT: the default locale groups with U+00A0, which the
            // Windows console renders as a replacement character.
            System.out.printf(java.util.Locale.ROOT, "[startup] %,d stocks already in the database "
                    + "(set SYNC_ASSETS_ON_START=true to refresh at boot)%n", stocks.count());
        }

        Scheduler scheduler = new Scheduler(alertService, importer, stocks, watchlists);
        scheduler.start();

        Api api = new Api(stocks, watchlists, alerts, marketData, portfolio, alertService, alpaca);

        Javalin app = Javalin.create(config -> {
            config.jsonMapper(new GsonMapper());
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/public";
                staticFiles.location = Location.CLASSPATH;
            });
            // The UI is one page with a client-side router. Serving index.html
            // for any unmatched path is what makes /AAPL and /portfolio work as
            // real URLs - shareable, bookmarkable, and correct on reload.
            config.spaRoot.addFile("/", "/public/index.html", Location.CLASSPATH);
            config.startup.showJavalinBanner = false;

            // Javalin 7 moved routing off the Javalin instance and into the
            // config block; handlers are registered against config.routes.
            api.register(config.routes);
        });

        app.start(Config.SERVER_PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[shutdown] stopping...");
            scheduler.close();
            app.stop();
            alpaca.shutdown();
            db.close();
            System.out.println("[shutdown] done");
        }, "ticker-shutdown"));

        System.out.println();
        System.out.println("  Ticker is running -> http://localhost:" + Config.SERVER_PORT);
        System.out.println("  Press Ctrl-C to stop.");
        System.out.println();
    }

    /**
     * Creates a starter watchlist the first time the app runs.
     *
     * <p>Only symbols actually present in the database are added, so this is a
     * no-op rather than an error on an empty or partially synced install.
     */
    private static void seedWatchlist(StockRepo stocks, WatchlistRepo watchlists) {
        if (!watchlists.isEmpty()) {
            return;
        }
        var created = watchlists.create("My Watchlist");
        int added = 0;
        for (String symbol : STARTER_SYMBOLS) {
            Stock stock = stocks.findBySymbol(symbol).orElse(null);
            if (stock != null) {
                watchlists.addItem(created.id(), stock.id());
                added++;
            }
        }
        System.out.printf("[startup] created starter watchlist with %d symbols%n", added);
    }

    private static void banner() {
        System.out.println("""

                  ______ _      __
                 /_  __/(_)____/ /_____ _____
                  / /  / // ___/ //_/ _ \\/ ___/
                 / /  / // /__/ ,< /  __/ /
                /_/  /_/ \\___/_/|_|\\___/_/     market watchlists, charts, paper trades
                """);
    }
}
