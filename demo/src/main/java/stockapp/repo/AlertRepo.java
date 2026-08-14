package stockapp.repo;

import stockapp.Db;
import stockapp.model.Alert;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Reads and writes {@code price_alert}. */
public final class AlertRepo {

    private final Db db;

    public AlertRepo(Db db) {
        this.db = db;
    }

    private static final String SELECT = """
            SELECT a.id, s.symbol, s.company, a.direction, a.threshold, a.note,
                   a.created_at, a.triggered_at, a.triggered_price
              FROM price_alert a
              JOIN stock s ON s.id = a.stock_id
            """;

    public List<Alert> listAll() {
        // Pending alerts first, then most recently fired.
        return query(SELECT + " ORDER BY (a.triggered_at IS NOT NULL), a.created_at DESC", ps -> {
        });
    }

    public Alert create(int stockId, String direction, BigDecimal threshold, String note) {
        String sql = """
                INSERT INTO price_alert (stock_id, direction, threshold, note)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """;
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, stockId);
            ps.setString(2, direction);
            ps.setBigDecimal(3, threshold);
            ps.setString(4, note);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int id = rs.getInt("id");
                return find(id).orElseThrow(() -> new IllegalStateException("Alert " + id + " vanished after insert"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create the alert: " + e.getMessage(), e);
        }
    }

    public java.util.Optional<Alert> find(int id) {
        List<Alert> found = query(SELECT + " WHERE a.id = ?", ps -> ps.setInt(1, id));
        return found.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(found.get(0));
    }

    public void delete(int id) {
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM price_alert WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not delete alert " + id, e);
        }
    }

    /** Alerts that have not fired yet, with the symbol needed to price them. */
    public List<Pending> pending() {
        String sql = """
                SELECT a.id, s.symbol, a.direction, a.threshold
                  FROM price_alert a
                  JOIN stock s ON s.id = a.stock_id
                 WHERE a.triggered_at IS NULL
                """;
        List<Pending> pending = new ArrayList<>();
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                pending.add(new Pending(rs.getInt("id"), rs.getString("symbol"),
                        rs.getString("direction"), rs.getBigDecimal("threshold")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not load pending alerts", e);
        }
        return pending;
    }

    public record Pending(int id, String symbol, String direction, BigDecimal threshold) {
    }

    /**
     * Marks an alert as fired.
     *
     * <p>The {@code triggered_at IS NULL} guard makes this idempotent: if two
     * evaluator runs overlap, only the first one wins.
     */
    public boolean markTriggered(int id, BigDecimal price) {
        String sql = "UPDATE price_alert SET triggered_at = now(), triggered_price = ? "
                + "WHERE id = ? AND triggered_at IS NULL";
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBigDecimal(1, price);
            ps.setInt(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not mark alert " + id + " as triggered", e);
        }
    }

    // ---------------------------------------------------------------- helpers

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<Alert> query(String sql, Binder binder) {
        List<Alert> alerts = new ArrayList<>();
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    alerts.add(new Alert(
                            rs.getInt("id"),
                            rs.getString("symbol"),
                            rs.getString("company"),
                            rs.getString("direction"),
                            rs.getBigDecimal("threshold"),
                            rs.getString("note"),
                            iso(rs.getTimestamp("created_at")),
                            iso(rs.getTimestamp("triggered_at")),
                            rs.getBigDecimal("triggered_price")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not load alerts", e);
        }
        return alerts;
    }

    private static String iso(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }
}
