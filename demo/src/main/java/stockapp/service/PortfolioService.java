package stockapp.service;

import stockapp.model.Candle;
import stockapp.model.Holding;
import stockapp.model.PortfolioSummary;
import stockapp.model.Quote;
import stockapp.model.Range;
import stockapp.model.Stock;
import stockapp.model.TradeRecord;
import stockapp.repo.PortfolioRepo;
import stockapp.repo.StockRepo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Values the paper portfolio and reconstructs its history.
 *
 * <p>All money arithmetic is {@link BigDecimal} with explicit rounding. Live
 * prices arrive as doubles from the market data API and are converted once, at
 * the boundary, using {@link BigDecimal#valueOf(double)} rather than the
 * {@code double} constructor so that 302.2 stays 302.20.
 */
public final class PortfolioService {

    private static final int MONEY_SCALE = 2;
    private static final int PERCENT_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PortfolioRepo portfolios;
    private final StockRepo stocks;
    private final MarketData marketData;
    private final Importer importer;
    private final String portfolioName;

    public PortfolioService(PortfolioRepo portfolios,
                            StockRepo stocks,
                            MarketData marketData,
                            Importer importer,
                            String portfolioName) {
        this.portfolios = portfolios;
        this.stocks = stocks;
        this.marketData = marketData;
        this.importer = importer;
        this.portfolioName = portfolioName;
    }

    // ---------------------------------------------------------------- summary

    /** The portfolio valued against the latest available quotes. */
    public PortfolioSummary summary() {
        PortfolioRepo.PortfolioRow row = portfolios.load(portfolioName);
        List<PortfolioRepo.PositionRow> positions = portfolios.positions(row.id());

        Map<String, Quote> quotes = positions.isEmpty()
                ? Map.of()
                : marketData.quotes(positions.stream().map(PortfolioRepo.PositionRow::symbol).toList());

        BigDecimal marketValue = BigDecimal.ZERO;
        BigDecimal dayChange = BigDecimal.ZERO;
        boolean stale = false;

        // First pass: value every position. Weights need the total, so they are
        // filled in on a second pass once marketValue is known.
        record Valued(PortfolioRepo.PositionRow position,
                      BigDecimal lastPrice,
                      BigDecimal value,
                      BigDecimal dayChange) {
        }
        List<Valued> valued = new ArrayList<>(positions.size());

        for (PortfolioRepo.PositionRow position : positions) {
            Quote quote = quotes.get(position.symbol().toUpperCase());
            BigDecimal lastPrice;
            BigDecimal positionDayChange = BigDecimal.ZERO;

            if (quote == null) {
                // No live price: fall back to cost so the total stays sane, and
                // flag the summary so the UI can say the numbers are partial.
                lastPrice = position.avgCost();
                stale = true;
            } else {
                lastPrice = money(quote.price());
                if (quote.change() != null) {
                    positionDayChange = money(BigDecimal.valueOf(quote.change()).multiply(position.quantity()));
                }
            }

            BigDecimal value = money(lastPrice.multiply(position.quantity()));
            marketValue = marketValue.add(value);
            dayChange = dayChange.add(positionDayChange);
            valued.add(new Valued(position, lastPrice, value, positionDayChange));
        }

        marketValue = money(marketValue);
        BigDecimal cash = money(row.cash());
        BigDecimal totalValue = money(cash.add(marketValue));
        BigDecimal startingCash = money(row.startingCash());
        BigDecimal totalPnl = money(totalValue.subtract(startingCash));

        List<Holding> holdings = new ArrayList<>(valued.size());
        for (Valued v : valued) {
            BigDecimal costBasis = money(v.position().avgCost().multiply(v.position().quantity()));
            BigDecimal unrealized = money(v.value().subtract(costBasis));
            holdings.add(new Holding(
                    v.position().symbol(),
                    v.position().company(),
                    tidyQuantity(v.position().quantity()),
                    v.position().avgCost(),
                    costBasis,
                    v.lastPrice(),
                    v.value(),
                    unrealized,
                    percent(unrealized, costBasis),
                    money(v.position().realizedPnl()),
                    v.dayChange(),
                    percent(v.dayChange(), v.value().subtract(v.dayChange())),
                    percent(v.value(), marketValue)));
        }
        // Biggest position first reads better than alphabetical.
        holdings.sort((a, b) -> b.marketValue().compareTo(a.marketValue()));

        // The day's move is a percentage of yesterday's total, not today's.
        BigDecimal previousTotal = totalValue.subtract(dayChange);

        return new PortfolioSummary(
                row.id(),
                row.name(),
                cash,
                startingCash,
                marketValue,
                totalValue,
                totalPnl,
                percent(totalPnl, startingCash),
                money(dayChange),
                percent(dayChange, previousTotal),
                portfolios.totalRealizedPnl(row.id()),
                holdings,
                stale);
    }

    // ----------------------------------------------------------------- orders

    /** Buys at the current last-trade price. */
    public TradeRecord buy(Stock stock, BigDecimal quantity, String note) {
        return execute(stock, "BUY", quantity, note);
    }

    /** Sells at the current last-trade price. */
    public TradeRecord sell(Stock stock, BigDecimal quantity, String note) {
        return execute(stock, "SELL", quantity, note);
    }

    private TradeRecord execute(Stock stock, String side, BigDecimal quantity, String note) {
        Quote quote = marketData.quote(stock.symbol());
        if (quote == null) {
            throw new PortfolioRepo.TradeRejected(
                    "No market price is available for " + stock.symbol() + " right now.");
        }
        PortfolioRepo.PortfolioRow row = portfolios.load(portfolioName);
        return portfolios.executeTrade(row.id(), stock, side, quantity, money(quote.price()), note);
    }

    /** The open position in one symbol, if any. Does not hit the market data API. */
    public java.util.Optional<PortfolioRepo.PositionRow> positionFor(Stock stock) {
        return portfolios.positions(portfolios.load(portfolioName).id()).stream()
                .filter(position -> position.stockId() == stock.id())
                .findFirst();
    }

    public List<TradeRecord> trades(int limit) {
        return portfolios.trades(portfolios.load(portfolioName).id(), limit);
    }

    public void reset() {
        portfolios.reset(portfolios.load(portfolioName).id());
    }

    // ---------------------------------------------------------------- history

    /** One point on the portfolio value curve. */
    public record ValuePoint(long time, BigDecimal value) {
    }

    /**
     * Portfolio value per trading day over the requested range.
     *
     * <p>Rebuilt from the trade log rather than stored: replay every fill in
     * order to get the cash balance and share count on each day, then value the
     * shares at that day's close. This means the curve is always consistent with
     * the trades, even after a correction, and there is no snapshot table to
     * drift out of sync.
     *
     * <p>The series never starts before the first trade - there is nothing
     * meaningful to plot before the portfolio existed.
     */
    public List<ValuePoint> history(Range range) {
        PortfolioRepo.PortfolioRow row = portfolios.load(portfolioName);
        List<PortfolioRepo.TradeLot> lots = portfolios.tradeLots(row.id());
        if (lots.isEmpty()) {
            return List.of();
        }

        LocalDate firstTradeDate = lots.get(0).executedAt().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate windowStart = today.minus(range.lookback());
        LocalDate start = windowStart.isBefore(firstTradeDate) ? firstTradeDate : windowStart;

        // Daily closes for everything ever held, backfilled if not stored yet.
        Map<Integer, NavigableMap<LocalDate, BigDecimal>> closes = new HashMap<>();
        TreeSet<LocalDate> tradingDays = new TreeSet<>();
        for (int stockId : lots.stream().map(PortfolioRepo.TradeLot::stockId).distinct().toList()) {
            NavigableMap<LocalDate, BigDecimal> series = dailyCloses(stockId, firstTradeDate);
            closes.put(stockId, series);
            tradingDays.addAll(series.headMap(today, true).tailMap(start, true).keySet());
        }
        if (tradingDays.isEmpty()) {
            return List.of();
        }

        // Replay the trade log day by day.
        BigDecimal cash = money(row.startingCash());
        Map<Integer, BigDecimal> shares = new LinkedHashMap<>();
        int lotIndex = 0;
        List<ValuePoint> series = new ArrayList<>(tradingDays.size());

        for (LocalDate day : tradingDays) {
            while (lotIndex < lots.size()) {
                PortfolioRepo.TradeLot lot = lots.get(lotIndex);
                LocalDate lotDate = lot.executedAt().atZone(ZoneOffset.UTC).toLocalDate();
                if (lotDate.isAfter(day)) {
                    break;
                }
                BigDecimal amount = money(lot.quantity().multiply(lot.price()));
                if ("BUY".equals(lot.side())) {
                    cash = cash.subtract(amount);
                    shares.merge(lot.stockId(), lot.quantity(), BigDecimal::add);
                } else {
                    cash = cash.add(amount);
                    shares.merge(lot.stockId(), lot.quantity().negate(), BigDecimal::add);
                }
                lotIndex++;
            }

            BigDecimal positionsValue = BigDecimal.ZERO;
            for (Map.Entry<Integer, BigDecimal> held : shares.entrySet()) {
                if (held.getValue().signum() == 0) {
                    continue;
                }
                BigDecimal close = closeOnOrBefore(closes.get(held.getKey()), day);
                if (close != null) {
                    positionsValue = positionsValue.add(close.multiply(held.getValue()));
                }
            }
            series.add(new ValuePoint(
                    day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                    money(cash.add(positionsValue))));
        }
        return series;
    }

    private NavigableMap<LocalDate, BigDecimal> dailyCloses(int stockId, LocalDate from) {
        stocks.findById(stockId).ifPresent(stock -> importer.ensureDailyCoverage(stock, from));
        NavigableMap<LocalDate, BigDecimal> series = new TreeMap<>();
        for (Candle candle : stocks.dailyBars(stockId, from)) {
            series.put(Instant.ofEpochMilli(candle.time()).atZone(ZoneOffset.UTC).toLocalDate(),
                    BigDecimal.valueOf(candle.close()));
        }
        return series;
    }

    /** Most recent close at or before {@code day}, so holidays forward-fill. */
    private static BigDecimal closeOnOrBefore(NavigableMap<LocalDate, BigDecimal> series, LocalDate day) {
        if (series == null) {
            return null;
        }
        Map.Entry<LocalDate, BigDecimal> entry = series.floorEntry(day);
        return entry == null ? null : entry.getValue();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Drops trailing zeros from a share count without letting it become
     * scientific notation.
     *
     * <p>{@code new BigDecimal("10.000000").stripTrailingZeros()} has scale -1,
     * which serialises as {@code 1E+1}. That parses back to 10 correctly, but
     * it looks broken in a response body and in anything reading the API by eye.
     */
    public static BigDecimal tidyQuantity(BigDecimal quantity) {
        BigDecimal stripped = (quantity == null ? BigDecimal.ZERO : quantity).stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped;
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** {@code part / whole * 100}, or zero when the denominator is zero. */
    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return BigDecimal.ZERO.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
        }
        return part.multiply(HUNDRED).divide(whole, PERCENT_SCALE, RoundingMode.HALF_UP);
    }
}
