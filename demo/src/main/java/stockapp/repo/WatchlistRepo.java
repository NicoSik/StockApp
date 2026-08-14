package stockapp.repo;

import stockapp.Db;
import stockapp.model.Watchlist;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads and writes {@code watchlist} and {@code watchlist_item}. */
public final class WatchlistRepo {

    private final Db db;

    public WatchlistRepo(Db db) {
        this.db = db;
    }

    /** Every watchlist with its symbols, in user order. */
    public List<Watchlist> listAll() {
        String sql = """
                SELECT w.id, w.name, s.symbol
                  FROM watchlist w
                  LEFT JOIN watchlist_item i ON i.watchlist_id = w.id
                  LEFT JOIN stock s          ON s.id = i.stock_id
                 ORDER BY w.sort_order, w.id, i.sort_order, s.symbol
                """;

        Map<Integer, String> names = new LinkedHashMap<>();
        Map<Integer, List<String>> symbols = new LinkedHashMap<>();

        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int id = rs.getInt("id");
                names.putIfAbsent(id, rs.getString("name"));
                symbols.computeIfAbsent(id, k -> new ArrayList<>());
                String symbol = rs.getString("symbol");
                if (symbol != null) {
                    symbols.get(id).add(symbol);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not load watchlists", e);
        }

        List<Watchlist> watchlists = new ArrayList<>(names.size());
        names.forEach((id, name) -> watchlists.add(new Watchlist(id, name, symbols.getOrDefault(id, List.of()))));
        return watchlists;
    }

    public Optional<Watchlist> find(int id) {
        return listAll().stream().filter(w -> w.id() == id).findFirst();
    }

    /** All distinct symbols across every watchlist - the set worth pre-fetching. */
    public List<String> allSymbols() {
        String sql = """
                SELECT DISTINCT s.symbol
                  FROM watchlist_item i
                  JOIN stock s ON s.id = i.stock_id
                 ORDER BY s.symbol
                """;
        List<String> symbols = new ArrayList<>();
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                symbols.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not load watched symbols", e);
        }
        return symbols;
    }

    public Watchlist create(String name) {
        String sql = """
                INSERT INTO watchlist (name, sort_order)
                VALUES (?, COALESCE((SELECT max(sort_order) + 1 FROM watchlist), 0))
                """;
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return new Watchlist(keys.getInt("id"), name, List.of());
                }
            }
            throw new IllegalStateException("Insert of watchlist \"" + name + "\" returned no id");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create watchlist \"" + name + "\": " + e.getMessage(), e);
        }
    }

    public void rename(int id, String name) {
        execute("UPDATE watchlist SET name = ? WHERE id = ?", ps -> {
            ps.setString(1, name);
            ps.setInt(2, id);
        }, "Could not rename watchlist " + id);
    }

    public void delete(int id) {
        execute("DELETE FROM watchlist WHERE id = ?", ps -> ps.setInt(1, id),
                "Could not delete watchlist " + id);
    }

    /** Appends a symbol; adding one that is already present is a no-op. */
    public void addItem(int watchlistId, int stockId) {
        String sql = """
                INSERT INTO watchlist_item (watchlist_id, stock_id, sort_order)
                VALUES (?, ?, COALESCE((SELECT max(sort_order) + 1 FROM watchlist_item WHERE watchlist_id = ?), 0))
                ON CONFLICT (watchlist_id, stock_id) DO NOTHING
                """;
        execute(sql, ps -> {
            ps.setInt(1, watchlistId);
            ps.setInt(2, stockId);
            ps.setInt(3, watchlistId);
        }, "Could not add stock " + stockId + " to watchlist " + watchlistId);
    }

    public void removeItem(int watchlistId, int stockId) {
        execute("DELETE FROM watchlist_item WHERE watchlist_id = ? AND stock_id = ?", ps -> {
            ps.setInt(1, watchlistId);
            ps.setInt(2, stockId);
        }, "Could not remove stock " + stockId + " from watchlist " + watchlistId);
    }

    /**
     * Rewrites the ordering of a list from an ordered array of stock ids.
     * Ids not currently in the list are ignored.
     */
    public void reorder(int watchlistId, List<Integer> stockIdsInOrder) {
        String sql = "UPDATE watchlist_item SET sort_order = ? WHERE watchlist_id = ? AND stock_id = ?";
        try (Connection conn = db.connection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < stockIdsInOrder.size(); i++) {
                    ps.setInt(1, i);
                    ps.setInt(2, watchlistId);
                    ps.setInt(3, stockIdsInOrder.get(i));
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not reorder watchlist " + watchlistId, e);
        }
    }

    public boolean isEmpty() {
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM watchlist");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getInt(1) == 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count watchlists", e);
        }
    }

    // ---------------------------------------------------------------- helpers

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private void execute(String sql, Binder binder, String errorMessage) {
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(errorMessage + ": " + e.getMessage(), e);
        }
    }
}
