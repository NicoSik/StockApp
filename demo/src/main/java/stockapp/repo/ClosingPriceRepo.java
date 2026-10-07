package stockapp.repo;

import stockapp.Db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Daily closing prices for priced holdings, in the instrument's own currency.
 *
 * <p>The holdings value history is rebuilt from these. They are stored so the
 * history page reads only the database: fetching dozens of series from Yahoo
 * would make it wait on the network on every visit.
 */
public final class ClosingPriceRepo {

    /** The first and last day stored for one symbol. */
    public record StoredRange(LocalDate first, LocalDate last) {
    }

    private final Db db;

    public ClosingPriceRepo(Db db) {
        this.db = db;
    }

    /** Stores closes for one symbol, replacing any already stored for those days. */
    public void save(String symbol, Map<LocalDate, BigDecimal> closes) {
        if (closes.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO instrument_close (symbol, day, close)
                VALUES (?, ?, ?)
                ON CONFLICT (symbol, day) DO UPDATE SET close = EXCLUDED.close
                """;
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map.Entry<LocalDate, BigDecimal> close : closes.entrySet()) {
                if (close.getValue() == null || close.getValue().signum() <= 0) {
                    continue;
                }
                ps.setString(1, symbol);
                ps.setDate(2, Date.valueOf(close.getKey()));
                ps.setBigDecimal(3, close.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store closes for " + symbol, e);
        }
    }

    /** The days already stored for a symbol; empty when there are none. */
    public Optional<StoredRange> storedRange(String symbol) {
        String sql = "SELECT min(day) AS first, max(day) AS last FROM instrument_close WHERE symbol = ?";
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getDate("first") == null) {
                    return Optional.empty();
                }
                return Optional.of(new StoredRange(rs.getDate("first").toLocalDate(),
                        rs.getDate("last").toLocalDate()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the stored range for " + symbol, e);
        }
    }

    /** Every stored close from {@code from} onwards, per symbol, oldest first. */
    public Map<String, NavigableMap<LocalDate, BigDecimal>> closes(Collection<String> symbols, LocalDate from) {
        Map<String, NavigableMap<LocalDate, BigDecimal>> closes = new LinkedHashMap<>();
        if (symbols.isEmpty()) {
            return closes;
        }
        String sql = """
                SELECT symbol, day, close FROM instrument_close
                 WHERE symbol = ANY(?) AND day >= ?
                 ORDER BY symbol, day
                """;
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("text", symbols.toArray(String[]::new)));
            ps.setDate(2, Date.valueOf(from));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    closes.computeIfAbsent(rs.getString("symbol"), symbol -> new TreeMap<>())
                            .put(rs.getDate("day").toLocalDate(), rs.getBigDecimal("close"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read stored closes", e);
        }
        return closes;
    }
}
