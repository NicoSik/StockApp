package stockapp.web;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import stockapp.Config;
import stockapp.alpaca.AlpacaClient;
import stockapp.alpaca.AlpacaException;
import stockapp.model.Alert;
import stockapp.model.Candles;
import stockapp.model.Quote;
import stockapp.model.Range;
import stockapp.model.Spark;
import stockapp.model.Stock;
import stockapp.model.TradeRecord;
import stockapp.model.Watchlist;
import stockapp.repo.AlertRepo;
import stockapp.repo.PortfolioRepo;
import stockapp.repo.StockRepo;
import stockapp.repo.WatchlistRepo;
import stockapp.service.AlertService;
import stockapp.service.MarketData;
import stockapp.service.PortfolioService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every HTTP endpoint the browser talks to.
 *
 * <p>The whole API is JSON. The previous version built HTML in Java string
 * concatenation, which meant no client-side state, a full page load per
 * interaction, and user-supplied symbols interpolated straight into markup.
 */
public final class Api {

    private static final int MAX_SEARCH_RESULTS = 12;
    private static final int MAX_ROW_SYMBOLS = 100;
    private static final int DEFAULT_TRADE_LIMIT = 50;

    private final StockRepo stocks;
    private final WatchlistRepo watchlists;
    private final AlertRepo alerts;
    private final MarketData marketData;
    private final PortfolioService portfolio;
    private final AlertService alertService;
    private final AlpacaClient alpaca;

    public Api(StockRepo stocks,
               WatchlistRepo watchlists,
               AlertRepo alerts,
               MarketData marketData,
               PortfolioService portfolio,
               AlertService alertService,
               AlpacaClient alpaca) {
        this.stocks = stocks;
        this.watchlists = watchlists;
        this.alerts = alerts;
        this.marketData = marketData;
        this.portfolio = portfolio;
        this.alertService = alertService;
        this.alpaca = alpaca;
    }

    public void register(Javalin app) {
        registerErrorHandlers(app);

        app.get("/api/health", this::health);
        app.get("/api/meta", this::meta);
        app.get("/api/market/clock", ctx -> ctx.json(marketData.clock()));

        app.get("/api/search", this::search);
        app.get("/api/quotes", this::quotes);
        app.get("/api/rows", this::rows);
        app.get("/api/stocks/{symbol}", this::stockDetail);
        app.get("/api/stocks/{symbol}/candles", this::candles);

        app.get("/api/watchlists", ctx -> ctx.json(watchlists.listAll()));
        app.post("/api/watchlists", this::createWatchlist);
        app.patch("/api/watchlists/{id}", this::renameWatchlist);
        app.delete("/api/watchlists/{id}", this::deleteWatchlist);
        app.post("/api/watchlists/{id}/items", this::addWatchlistItem);
        app.delete("/api/watchlists/{id}/items/{symbol}", this::removeWatchlistItem);
        app.put("/api/watchlists/{id}/order", this::reorderWatchlist);

        app.get("/api/portfolio", ctx -> ctx.json(portfolio.summary()));
        app.get("/api/portfolio/history", this::portfolioHistory);
        app.get("/api/portfolio/trades", this::portfolioTrades);
        app.post("/api/portfolio/orders", this::placeOrder);
        app.post("/api/portfolio/reset", this::resetPortfolio);

        app.get("/api/alerts", ctx -> ctx.json(alerts.listAll()));
        app.post("/api/alerts", this::createAlert);
        app.delete("/api/alerts/{id}", this::deleteAlert);
        app.post("/api/alerts/evaluate", ctx -> ctx.json(alertService.evaluate()));

        // Registered last, so it only catches paths no real route matched.
        // Without it the SPA fallback would answer an unknown /api/... GET with
        // index.html, and the client would try to parse HTML as JSON.
        app.get("/api/*", ctx -> {
            throw new NotFound("No such endpoint: " + ctx.path());
        });
    }

    // ------------------------------------------------------------------ meta

    private void health(Context ctx) {
        ctx.json(Map.of("status", "ok", "time", System.currentTimeMillis()));
    }

    private void meta(Context ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stockCount", stocks.count());
        body.put("feed", alpaca.activeFeed());
        body.put("clock", marketData.clock());
        body.put("startingCash", new BigDecimal(Config.PAPER_STARTING_CASH));
        ctx.json(body);
    }

    // ---------------------------------------------------------------- search

    private void search(Context ctx) {
        String query = ctx.queryParam("q");
        if (query == null || query.isBlank()) {
            ctx.json(List.of());
            return;
        }
        int limit = clamp(intParam(ctx, "limit", MAX_SEARCH_RESULTS), 1, 50);
        ctx.json(stocks.search(query, limit));
    }

    // ---------------------------------------------------------------- quotes

    private void quotes(Context ctx) {
        List<String> symbols = symbolsParam(ctx);
        ctx.json(symbols.isEmpty() ? Map.of() : marketData.quotes(symbols));
    }

    /** A watchlist row: identity, live quote and sparkline in one response. */
    public record Row(String symbol, String company, String market, Quote quote, Spark spark) {
    }

    /**
     * Enriched rows for a set of symbols.
     *
     * <p>Backs both the watchlist rail and the holdings table. Fetching quotes
     * and sparklines together means one request per refresh regardless of how
     * many rows are on screen.
     */
    private void rows(Context ctx) {
        List<String> symbols = symbolsParam(ctx);
        if (symbols.isEmpty()) {
            ctx.json(List.of());
            return;
        }

        Map<String, Quote> quotes = marketData.quotes(symbols);
        Map<String, Spark> sparks = marketData.sparklines(symbols);

        List<Row> rows = new ArrayList<>(symbols.size());
        for (String symbol : symbols) {
            Stock stock = stocks.findBySymbol(symbol).orElse(null);
            if (stock == null) {
                continue;
            }
            rows.add(new Row(stock.symbol(), stock.company(), stock.market(),
                    quotes.get(stock.symbol().toUpperCase()), sparks.get(stock.symbol().toUpperCase())));
        }
        ctx.json(rows);
    }

    // ----------------------------------------------------------------- stock

    private void stockDetail(Context ctx) {
        Stock stock = requireStock(ctx.pathParam("symbol"));
        Quote quote = marketData.quote(stock.symbol());

        List<Integer> memberOf = watchlists.listAll().stream()
                .filter(list -> list.symbols().contains(stock.symbol()))
                .map(Watchlist::id)
                .toList();

        // The held quantity drives the trade panel's sell side. Read straight
        // from the position table: valuing the entire portfolio here would
        // fetch a quote for every unrelated holding on every stock page view.
        Map<String, Object> position = portfolio.positionFor(stock)
                .<Map<String, Object>>map(held -> Map.of(
                        "quantity", PortfolioService.tidyQuantity(held.quantity()),
                        "avgCost", held.avgCost(),
                        "realizedPnl", held.realizedPnl()))
                .orElse(null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stock", stock);
        body.put("quote", quote);
        body.put("watchlistIds", memberOf);
        body.put("position", position);
        ctx.json(body);
    }

    private void candles(Context ctx) {
        Stock stock = requireStock(ctx.pathParam("symbol"));
        Range range = Range.parse(ctx.queryParam("range"));
        Candles candles = marketData.candles(stock, range);
        ctx.json(candles);
    }

    // ------------------------------------------------------------ watchlists

    private void createWatchlist(Context ctx) {
        String name = Json.requireString(Json.parseObject(ctx.body()), "name");
        ctx.status(HttpStatus.CREATED).json(watchlists.create(name));
    }

    private void renameWatchlist(Context ctx) {
        int id = intPathParam(ctx, "id");
        String name = Json.requireString(Json.parseObject(ctx.body()), "name");
        watchlists.rename(id, name);
        ctx.json(requireWatchlist(id));
    }

    private void deleteWatchlist(Context ctx) {
        watchlists.delete(intPathParam(ctx, "id"));
        ctx.status(HttpStatus.NO_CONTENT);
    }

    private void addWatchlistItem(Context ctx) {
        int id = intPathParam(ctx, "id");
        requireWatchlist(id);
        Stock stock = requireStock(Json.requireString(Json.parseObject(ctx.body()), "symbol"));
        watchlists.addItem(id, stock.id());
        ctx.json(requireWatchlist(id));
    }

    private void removeWatchlistItem(Context ctx) {
        int id = intPathParam(ctx, "id");
        requireWatchlist(id);
        Stock stock = requireStock(ctx.pathParam("symbol"));
        watchlists.removeItem(id, stock.id());
        ctx.json(requireWatchlist(id));
    }

    private void reorderWatchlist(Context ctx) {
        int id = intPathParam(ctx, "id");
        requireWatchlist(id);
        var body = Json.parseObject(ctx.body());
        if (!body.has("symbols") || !body.get("symbols").isJsonArray()) {
            throw new Json.BadRequest("\"symbols\" must be an array of ticker symbols.");
        }
        List<Integer> stockIds = new ArrayList<>();
        body.getAsJsonArray("symbols").forEach(element ->
                stocks.findBySymbol(element.getAsString()).ifPresent(stock -> stockIds.add(stock.id())));
        watchlists.reorder(id, stockIds);
        ctx.json(requireWatchlist(id));
    }

    // ------------------------------------------------------------- portfolio

    private void portfolioHistory(Context ctx) {
        Range range = Range.parse(ctx.queryParam("range"));
        ctx.json(Map.of("range", range.label(), "points", portfolio.history(range)));
    }

    private void portfolioTrades(Context ctx) {
        int limit = clamp(intParam(ctx, "limit", DEFAULT_TRADE_LIMIT), 1, 500);
        ctx.json(portfolio.trades(limit));
    }

    private void placeOrder(Context ctx) {
        var body = Json.parseObject(ctx.body());
        Stock stock = requireStock(Json.requireString(body, "symbol"));
        String side = Json.requireOneOf(body, "side", "BUY", "SELL");
        BigDecimal quantity = Json.requirePositiveDecimal(body, "quantity");
        String note = Json.optString(body, "note");

        TradeRecord trade = side.equals("BUY")
                ? portfolio.buy(stock, quantity, note)
                : portfolio.sell(stock, quantity, note);

        // Returning the fresh summary saves the client a follow-up round trip.
        ctx.status(HttpStatus.CREATED).json(Map.of("trade", trade, "portfolio", portfolio.summary()));
    }

    private void resetPortfolio(Context ctx) {
        portfolio.reset();
        ctx.json(portfolio.summary());
    }

    // ---------------------------------------------------------------- alerts

    private void createAlert(Context ctx) {
        var body = Json.parseObject(ctx.body());
        Stock stock = requireStock(Json.requireString(body, "symbol"));
        String direction = Json.requireOneOf(body, "direction", "ABOVE", "BELOW");
        BigDecimal threshold = Json.requirePositiveDecimal(body, "threshold");
        Alert alert = alerts.create(stock.id(), direction, threshold, Json.optString(body, "note"));
        ctx.status(HttpStatus.CREATED).json(alert);
    }

    private void deleteAlert(Context ctx) {
        alerts.delete(intPathParam(ctx, "id"));
        ctx.status(HttpStatus.NO_CONTENT);
    }

    // --------------------------------------------------------------- helpers

    /** Raised when a path refers to something that does not exist. */
    public static class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    private Stock requireStock(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new Json.BadRequest("A ticker symbol is required.");
        }
        return stocks.findBySymbol(symbol.trim())
                .orElseThrow(() -> new NotFound("No stock named \"" + symbol.trim().toUpperCase(Locale.ROOT)
                        + "\" is in the database."));
    }

    private Watchlist requireWatchlist(int id) {
        return watchlists.find(id).orElseThrow(() -> new NotFound("Watchlist " + id + " does not exist."));
    }

    private static List<String> symbolsParam(Context ctx) {
        String raw = ctx.queryParam("symbols");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .distinct()
                .limit(MAX_ROW_SYMBOLS)
                .toList();
    }

    private static int intParam(Context ctx, String name, int defaultValue) {
        String raw = ctx.queryParam(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new Json.BadRequest("\"" + name + "\" must be a whole number.");
        }
    }

    private static int intPathParam(Context ctx, String name) {
        try {
            return Integer.parseInt(ctx.pathParam(name));
        } catch (NumberFormatException e) {
            throw new NotFound("\"" + ctx.pathParam(name) + "\" is not a valid " + name + ".");
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // --------------------------------------------------------- error mapping

    /**
     * Maps exceptions to status codes and a uniform {@code {"error": "..."}}
     * body, so the client has exactly one shape to handle.
     */
    private void registerErrorHandlers(Javalin app) {
        app.exception(Json.BadRequest.class, (e, ctx) ->
                fail(ctx, HttpStatus.BAD_REQUEST, e.getMessage()));

        app.exception(NotFound.class, (e, ctx) ->
                fail(ctx, HttpStatus.NOT_FOUND, e.getMessage()));

        // A rejected order is a valid request the portfolio refused, not a bug.
        app.exception(PortfolioRepo.TradeRejected.class, (e, ctx) ->
                fail(ctx, HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage()));

        app.exception(AlpacaException.class, (e, ctx) -> {
            System.out.println("[api] upstream failure: " + e.getMessage());
            fail(ctx, HttpStatus.BAD_GATEWAY, "The market data provider is not responding. Try again shortly.");
        });

        app.exception(Exception.class, (e, ctx) -> {
            // Unexpected: log the stack trace for us, send a plain message out.
            System.err.println("[api] unhandled error on " + ctx.method() + " " + ctx.path());
            e.printStackTrace();
            fail(ctx, HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong handling that request.");
        });
    }

    private static void fail(Context ctx, HttpStatus status, String message) {
        ctx.status(status).json(Map.of("error", message == null ? status.getMessage() : message));
    }
}
