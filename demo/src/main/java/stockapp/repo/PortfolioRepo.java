package stockapp.repo;

import stockapp.Db;
import stockapp.model.Stock;
import stockapp.model.TradeRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The paper portfolio's storage layer.
 *
 * <p>{@link #executeTrade} is the only place cash, trades and positions change,
 * and it does all three inside one transaction, so the invariant
 * "cash + cost basis of positions == starting cash + realised P&amp;L" always
 * holds.
 */
public final class PortfolioRepo {

    /** Currency is rounded to cents; share counts allow fractional trading. */
    public static final int MONEY_SCALE = 2;
    public static final int QUANTITY_SCALE = 6;

    private final Db db;

    public PortfolioRepo(Db db) {
        this.db = db;
    }

    public record PortfolioRow(int id, String name, BigDecimal cash, BigDecimal startingCash) {
    }

    public record PositionRow(int stockId,
                              String symbol,
                              String company,
                              BigDecimal quantity,
                              BigDecimal avgCost,
                              BigDecimal realizedPnl) {
    }

    /** Raised for a rejected order; the message is safe to show to the user. */
    public static class TradeRejected extends RuntimeException {
        public TradeRejected(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------- portfolio

    /** Returns the single local portfolio, creating it on first run. */
    public PortfolioRow ensurePortfolio(String name, BigDecimal startingCash) {
        String insert = """
                INSERT INTO portfolio (name, starting_cash, cash)
                VALUES (?, ?, ?)
                ON CONFLICT (name) DO NOTHING
                """;
        try (Connection conn = db.connection()) {
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                ps.setString(1, name);
                ps.setBigDecimal(2, money(startingCash));
                ps.setBigDecimal(3, money(startingCash));
                ps.executeUpdate();
            }
            return load(conn, name);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise the paper portfolio", e);
        }
    }

    public PortfolioRow load(String name) {
        try (Connection conn = db.connection()) {
            return load(conn, name);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not load portfolio \"" + name + "\"", e);
        }
    }

    private PortfolioRow load(Connection conn, String name) throws SQLException {
        String sql = "SELECT id, name, cash, starting_cash FROM portfolio WHERE name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Portfolio \"" + name + "\" does not exist");
                }
                return new PortfolioRow(rs.getInt("id"), rs.getString("name"),
                        rs.getBigDecimal("cash"), rs.getBigDecimal("starting_cash"));
            }
        }
    }

    // -------------------------------------------------------------- positions

    /** Open positions only; fully closed rows are kept for realised P&L but not listed. */
    public List<PositionRow> positions(int portfolioId) {
        String sql = """
                SELECT p.stock_id, s.symbol, s.company, p.quantity, p.avg_cost, p.realized_pnl
                  FROM position p
                  JOIN stock s ON s.id = p.stock_id
                 WHERE p.portfolio_id = ? AND p.quantity > 0
                 ORDER BY s.symbol
                """;
        List<PositionRow> rows = new ArrayList<>();
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, portfolioId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new PositionRow(
                            rs.getInt("stock_id"),
                            rs.getString("symbol"),
                            rs.getString("company"),
                            rs.getBigDecimal("quantity"),
                            rs.getBigDecimal("avg_cost"),
                            rs.getBigDecimal("realized_pnl")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not load positions", e);
        }
        return rows;
    }

    /** Realised P&L across every position, including closed ones. */
    public BigDecimal totalRealizedPnl(int portfolioId) {
        String sql = "SELECT COALESCE(sum(realized_pnl), 0) FROM position WHERE portfolio_id = ?";
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, portfolioId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? money(rs.getBigDecimal(1)) : BigDecimal.ZERO;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not total realised P&L", e);
        }
    }

    // ----------------------------------------------------------------- trades

    /**
     * Records a simulated fill and moves cash and the position to match.
     *
     * @param side  {@code BUY} or {@code SELL}
     * @param price the fill price, normally the current last-trade price
     * @throws TradeRejected when buying power or share count is insufficient
     */
    public TradeRecord executeTrade(int portfolioId, Stock stock, String side,
                                    BigDecimal quantity, BigDecimal price, String note) {
        BigDecimal qty = quantity.setScale(QUANTITY_SCALE, RoundingMode.DOWN);
        BigDecimal fillPrice = money(price);
        if (qty.signum() <= 0) {
            throw new TradeRejected("Quantity must be greater than zero.");
        }
        if (fillPrice.signum() <= 0) {
            throw new TradeRejected("No current price is available for " + stock.symbol() + ".");
        }
        BigDecimal amount = money(qty.multiply(fillPrice));

        try (Connection conn = db.connection()) {
            conn.setAutoCommit(false);
            try {
                // Lock the portfolio row so two concurrent orders cannot both
                // pass the buying-power check against the same cash balance.
                BigDecimal cash = lockCash(conn, portfolioId);
                PositionState position = lockPosition(conn, portfolioId, stock.id());

                BigDecimal newCash;
                BigDecimal newQuantity;
                BigDecimal newAvgCost;
                BigDecimal newRealized = position.realizedPnl;

                if ("BUY".equals(side)) {
                    if (amount.compareTo(cash) > 0) {
                        throw new TradeRejected("Not enough buying power: this order costs %s but only %s is available."
                                .formatted(amount.toPlainString(), cash.toPlainString()));
                    }
                    newCash = cash.subtract(amount);
                    newQuantity = position.quantity.add(qty);
                    // Weighted average of the existing basis and the new lot.
                    BigDecimal existingBasis = position.quantity.multiply(position.avgCost);
                    newAvgCost = existingBasis.add(amount)
                            .divide(newQuantity, MONEY_SCALE + 4, RoundingMode.HALF_UP)
                            .setScale(4, RoundingMode.HALF_UP);
                } else {
                    if (qty.compareTo(position.quantity) > 0) {
                        throw new TradeRejected("You hold %s %s; cannot sell %s."
                                .formatted(trim(position.quantity), stock.symbol(), trim(qty)));
                    }
                    newCash = cash.add(amount);
                    newQuantity = position.quantity.subtract(qty);
                    newAvgCost = position.avgCost;
                    newRealized = position.realizedPnl.add(
                            money(fillPrice.subtract(position.avgCost).multiply(qty)));
                }

                updateCash(conn, portfolioId, newCash);
                upsertPosition(conn, portfolioId, stock.id(), newQuantity, newAvgCost, newRealized);
                TradeRecord trade = insertTrade(conn, portfolioId, stock, side, qty, fillPrice, amount, note);

                conn.commit();
                return trade;
            } catch (SQLException | TradeRejected e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record the trade: " + e.getMessage(), e);
        }
    }

    private record PositionState(BigDecimal quantity, BigDecimal avgCost, BigDecimal realizedPnl) {
    }

    private BigDecimal lockCash(Connection conn, int portfolioId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT cash FROM portfolio WHERE id = ? FOR UPDATE")) {
            ps.setInt(1, portfolioId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new TradeRejected("Portfolio " + portfolioId + " does not exist.");
                }
                return money(rs.getBigDecimal("cash"));
            }
        }
    }

    private PositionState lockPosition(Connection conn, int portfolioId, int stockId) throws SQLException {
        String sql = "SELECT quantity, avg_cost, realized_pnl FROM position "
                + "WHERE portfolio_id = ? AND stock_id = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, portfolioId);
            ps.setInt(2, stockId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PositionState(rs.getBigDecimal("quantity"),
                            rs.getBigDecimal("avg_cost"), rs.getBigDecimal("realized_pnl"));
                }
            }
        }
        return new PositionState(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private void updateCash(Connection conn, int portfolioId, BigDecimal cash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE portfolio SET cash = ? WHERE id = ?")) {
            ps.setBigDecimal(1, money(cash));
            ps.setInt(2, portfolioId);
            ps.executeUpdate();
        }
    }

    private void upsertPosition(Connection conn, int portfolioId, int stockId,
                                BigDecimal quantity, BigDecimal avgCost, BigDecimal realizedPnl) throws SQLException {
        String sql = """
                INSERT INTO position (portfolio_id, stock_id, quantity, avg_cost, realized_pnl, updated_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (portfolio_id, stock_id) DO UPDATE
                    SET quantity     = EXCLUDED.quantity,
                        avg_cost     = EXCLUDED.avg_cost,
                        realized_pnl = EXCLUDED.realized_pnl,
                        updated_at   = now()
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, portfolioId);
            ps.setInt(2, stockId);
            ps.setBigDecimal(3, quantity.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP));
            ps.setBigDecimal(4, avgCost.setScale(4, RoundingMode.HALF_UP));
            ps.setBigDecimal(5, money(realizedPnl));
            ps.executeUpdate();
        }
    }

    private TradeRecord insertTrade(Connection conn, int portfolioId, Stock stock, String side,
                                    BigDecimal quantity, BigDecimal price, BigDecimal amount,
                                    String note) throws SQLException {
        String sql = """
                INSERT INTO trade (portfolio_id, stock_id, symbol, side, quantity, price, note)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id, executed_at
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, portfolioId);
            ps.setInt(2, stock.id());
            ps.setString(3, stock.symbol());
            ps.setString(4, side);
            ps.setBigDecimal(5, quantity);
            ps.setBigDecimal(6, price);
            ps.setString(7, note);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Timestamp executedAt = rs.getTimestamp("executed_at");
                return new TradeRecord(
                        rs.getLong("id"),
                        stock.symbol(),
                        stock.company(),
                        side,
                        quantity,
                        price,
                        "BUY".equals(side) ? amount.negate() : amount,
                        executedAt.toInstant().toString(),
                        note);
            }
        }
    }

    public List<TradeRecord> trades(int portfolioId, int limit) {
        String sql = """
                SELECT t.id, t.symbol, COALESCE(s.company, t.symbol) AS company,
                       t.side, t.quantity, t.price, t.executed_at, t.note
                  FROM trade t
                  LEFT JOIN stock s ON s.id = t.stock_id
                 WHERE t.portfolio_id = ?
                 ORDER BY t.executed_at DESC, t.id DESC
                 LIMIT ?
                """;
        List<TradeRecord> trades = new ArrayList<>();
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, portfolioId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BigDecimal quantity = rs.getBigDecimal("quantity");
                    BigDecimal price = rs.getBigDecimal("price");
                    BigDecimal amount = money(quantity.multiply(price));
                    String side = rs.getString("side");
                    trades.add(new TradeRecord(
                            rs.getLong("id"),
                            rs.getString("symbol"),
                            rs.getString("company"),
                            side,
                            quantity,
                            price,
                            "BUY".equals(side) ? amount.negate() : amount,
                            rs.getTimestamp("executed_at").toInstant().toString(),
                            rs.getString("note")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not load trade history", e);
        }
        return trades;
    }

    /** Every trade oldest-first, used to reconstruct historical portfolio value. */
    public List<TradeLot> tradeLots(int portfolioId) {
        String sql = """
                SELECT stock_id, side, quantity, price, executed_at
                  FROM trade
                 WHERE portfolio_id = ? AND stock_id IS NOT NULL
                 ORDER BY executed_at ASC, id ASC
                """;
        List<TradeLot> lots = new ArrayList<>();
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, portfolioId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lots.add(new TradeLot(
                            rs.getInt("stock_id"),
                            rs.getString("side"),
                            rs.getBigDecimal("quantity"),
                            rs.getBigDecimal("price"),
                            rs.getTimestamp("executed_at").toInstant()));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not load trade lots", e);
        }
        return lots;
    }

    public record TradeLot(int stockId, String side, BigDecimal quantity, BigDecimal price, Instant executedAt) {
    }

    /** Wipes trades and positions and restores the opening cash balance. */
    public void reset(int portfolioId) {
        try (Connection conn = db.connection()) {
            conn.setAutoCommit(false);
            try {
                exec(conn, "DELETE FROM trade WHERE portfolio_id = ?", portfolioId);
                exec(conn, "DELETE FROM position WHERE portfolio_id = ?", portfolioId);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE portfolio SET cash = starting_cash WHERE id = ?")) {
                    ps.setInt(1, portfolioId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not reset the portfolio", e);
        }
    }

    private static void exec(Connection conn, String sql, int portfolioId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, portfolioId);
            ps.executeUpdate();
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String trim(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
