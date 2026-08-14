package stockapp.repo;

import stockapp.Db;
import stockapp.alpaca.AlpacaClient;
import stockapp.model.Candle;
import stockapp.model.Stock;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Reads and writes the {@code stock} and {@code stock_price} tables. */
public final class StockRepo {

    private final Db db;

    public StockRepo(Db db) {
        this.db = db;
    }

    // ---------------------------------------------------------------- lookups

    public Optional<Stock> findBySymbol(String symbol) {
        String sql = "SELECT id, symbol, company, market FROM stock WHERE upper(symbol) = upper(?) LIMIT 1";
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readStock(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lookup failed for symbol " + symbol, e);
        }
    }

    public Optional<Stock> findById(int id) {
        String sql = "SELECT id, symbol, company, market FROM stock WHERE id = ?";
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readStock(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lookup failed for stock id " + id, e);
        }
    }

    /**
     * Ranked symbol/company search.
     *
     * <p>Ordering is what makes the search feel right: an exact ticker beats a
     * ticker prefix, which beats a company-name prefix, which beats a substring
     * match anywhere. Listed exchanges outrank OTC at equal relevance, and
     * shorter tickers win ties, so "A" surfaces "A" and "AA" rather than
     * whatever the database happened to store first.
     */
    public List<Stock> search(String term, int limit) {
        String query = term.trim().toUpperCase(Locale.ROOT);
        if (query.isEmpty()) {
            return List.of();
        }
        String prefix = query + "%";
        String contains = "%" + query + "%";

        String sql = """
                SELECT id, symbol, company, market,
                       CASE
                           WHEN upper(symbol) = ?            THEN 0
                           WHEN upper(symbol) LIKE ?         THEN 1
                           WHEN upper(company) LIKE ?        THEN 2
                           WHEN upper(symbol) LIKE ?         THEN 3
                           ELSE 4
                       END AS relevance,
                       CASE WHEN market IN ('NASDAQ', 'NYSE', 'ARCA', 'AMEX', 'BATS') THEN 0 ELSE 1 END AS venue
                  FROM stock
                 WHERE upper(symbol) LIKE ? OR upper(company) LIKE ?
                 ORDER BY relevance, venue, length(symbol), symbol
                 LIMIT ?
                """;

        List<Stock> results = new ArrayList<>();
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, query);
            ps.setString(2, prefix);
            ps.setString(3, prefix);
            ps.setString(4, contains);
            ps.setString(5, contains);
            ps.setString(6, contains);
            ps.setInt(7, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(readStock(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Search failed for \"" + term + "\"", e);
        }
        return results;
    }

    public int count() {
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM stock");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count stocks", e);
        }
    }

    private static Stock readStock(ResultSet rs) throws SQLException {
        return new Stock(rs.getInt("id"), rs.getString("symbol"), rs.getString("company"), rs.getString("market"));
    }

    // ------------------------------------------------------------ asset sync

    /**
     * Inserts new assets and refreshes the metadata of existing ones.
     *
     * @return how many rows were inserted or changed
     */
    public int upsertAssets(List<AlpacaClient.Asset> assets) {
        String sql = """
                INSERT INTO stock (symbol, company, market, tradable, fractionable, asset_class, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (symbol) DO UPDATE
                    SET company      = EXCLUDED.company,
                        market       = EXCLUDED.market,
                        tradable     = EXCLUDED.tradable,
                        fractionable = EXCLUDED.fractionable,
                        asset_class  = EXCLUDED.asset_class,
                        updated_at   = now()
                  WHERE stock.company      IS DISTINCT FROM EXCLUDED.company
                     OR stock.market       IS DISTINCT FROM EXCLUDED.market
                     OR stock.tradable     IS DISTINCT FROM EXCLUDED.tradable
                     OR stock.fractionable IS DISTINCT FROM EXCLUDED.fractionable
                """;

        try (Connection conn = db.connection()) {
            conn.setAutoCommit(false);
            int changed = 0;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int batched = 0;
                for (AlpacaClient.Asset asset : assets) {
                    ps.setString(1, asset.symbol());
                    ps.setString(2, asset.name());
                    ps.setString(3, asset.exchange());
                    ps.setBoolean(4, asset.tradable());
                    ps.setBoolean(5, asset.fractionable());
                    ps.setString(6, asset.assetClass());
                    ps.addBatch();
                    if (++batched % 1000 == 0) {
                        changed += countUpdates(ps.executeBatch());
                    }
                }
                changed += countUpdates(ps.executeBatch());
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
            return changed;
        } catch (SQLException e) {
            throw new IllegalStateException("Asset sync failed", e);
        }
    }

    private static int countUpdates(int[] results) {
        int total = 0;
        for (int result : results) {
            // A no-op ON CONFLICT ... WHERE reports 0; SUCCESS_NO_INFO reports -2.
            if (result > 0) {
                total += result;
            }
        }
        return total;
    }

    // ------------------------------------------------------------ daily bars

    /**
     * Persists daily bars, overwriting any bar already stored for the same day
     * so that a re-import corrects rather than duplicates.
     */
    public int saveDailyBars(int stockId, List<Candle> candles) {
        if (candles.isEmpty()) {
            return 0;
        }
        String sql = """
                INSERT INTO stock_price
                       (stock_id, date, open_price, high_price, low_price, close_price, price, volume)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (stock_id, date) DO UPDATE
                    SET open_price  = EXCLUDED.open_price,
                        high_price  = EXCLUDED.high_price,
                        low_price   = EXCLUDED.low_price,
                        close_price = EXCLUDED.close_price,
                        price       = EXCLUDED.price,
                        volume      = EXCLUDED.volume
                """;

        try (Connection conn = db.connection()) {
            conn.setAutoCommit(false);
            int written;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (Candle candle : candles) {
                    LocalDate date = Instant.ofEpochMilli(candle.time()).atZone(ZoneOffset.UTC).toLocalDate();
                    ps.setInt(1, stockId);
                    ps.setDate(2, Date.valueOf(date));
                    ps.setDouble(3, candle.open());
                    ps.setDouble(4, candle.high());
                    ps.setDouble(5, candle.low());
                    ps.setDouble(6, candle.close());
                    ps.setDouble(7, candle.close());
                    ps.setLong(8, candle.volume());
                    ps.addBatch();
                }
                written = countUpdates(ps.executeBatch());
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
            return written;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store bars for stock " + stockId, e);
        }
    }

    /** Stored daily bars from {@code from} (inclusive) onwards, oldest first. */
    public List<Candle> dailyBars(int stockId, LocalDate from) {
        String sql = """
                SELECT date, open_price, high_price, low_price, close_price, volume
                  FROM stock_price
                 WHERE stock_id = ? AND date >= ? AND close_price IS NOT NULL
                 ORDER BY date ASC
                """;

        List<Candle> candles = new ArrayList<>();
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, stockId);
            ps.setDate(2, Date.valueOf(from));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double close = rs.getDouble("close_price");
                    candles.add(new Candle(
                            rs.getDate("date").toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                            orElse(rs.getObject("open_price"), close),
                            orElse(rs.getObject("high_price"), close),
                            orElse(rs.getObject("low_price"), close),
                            close,
                            rs.getLong("volume")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read stored bars for stock " + stockId, e);
        }
        return candles;
    }

    /**
     * The most recent stored close for each of the given stock ids, used to
     * value the portfolio when the market data API cannot be reached.
     */
    public java.util.Map<Integer, Double> latestStoredCloses(List<Integer> stockIds) {
        java.util.Map<Integer, Double> closes = new java.util.HashMap<>();
        if (stockIds.isEmpty()) {
            return closes;
        }
        String sql = """
                SELECT DISTINCT ON (stock_id) stock_id, close_price
                  FROM stock_price
                 WHERE stock_id = ANY (?) AND close_price IS NOT NULL
                 ORDER BY stock_id, date DESC
                """;
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("integer", stockIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    closes.put(rs.getInt("stock_id"), rs.getDouble("close_price"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read stored closes", e);
        }
        return closes;
    }

    private static double orElse(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
}
