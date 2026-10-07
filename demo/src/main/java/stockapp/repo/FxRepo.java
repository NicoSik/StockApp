package stockapp.repo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import stockapp.Db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Stores Norges Bank rates so the app can still value a portfolio offline. */
public final class FxRepo {

    private static final Logger log = LoggerFactory.getLogger(FxRepo.class);

    private final Db db;

    public FxRepo(Db db) {
        this.db = db;
    }

    /** Rates are normalised to "1 base = rate NOK" before they get here. */
    public void save(LocalDate asOf, Map<String, BigDecimal> rates) {
        String sql = """
                INSERT INTO fx_rate (base, quote, as_of, rate)
                VALUES (?, 'NOK', ?, ?)
                ON CONFLICT (base, quote, as_of) DO UPDATE SET rate = EXCLUDED.rate
                """;
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map.Entry<String, BigDecimal> entry : rates.entrySet()) {
                ps.setString(1, entry.getKey());
                ps.setDate(2, Date.valueOf(asOf));
                ps.setBigDecimal(3, entry.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            // Caching rates is an optimisation; failing to store them must not
            // break a valuation that already has the numbers in hand.
            log.warn("Could not cache rates: {}", e.getMessage());
        }
    }

    /** Stores a series of dated rates per currency, replacing any already stored. */
    public void saveHistory(Map<String, NavigableMap<LocalDate, BigDecimal>> history) {
        Map<LocalDate, Map<String, BigDecimal>> byDate = new TreeMap<>();
        history.forEach((currency, series) -> series.forEach((day, rate) ->
                byDate.computeIfAbsent(day, d -> new LinkedHashMap<>()).put(currency, rate)));
        byDate.forEach(this::save);
    }

    /** Every stored rate from {@code from} onwards, per currency, oldest first. */
    public Map<String, NavigableMap<LocalDate, BigDecimal>> history(LocalDate from) {
        String sql = """
                SELECT base, as_of, rate FROM fx_rate
                 WHERE quote = 'NOK' AND as_of >= ?
                 ORDER BY base, as_of
                """;
        Map<String, NavigableMap<LocalDate, BigDecimal>> history = new LinkedHashMap<>();
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(from));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    history.computeIfAbsent(rs.getString("base"), base -> new TreeMap<>())
                            .put(rs.getDate("as_of").toLocalDate(), rs.getBigDecimal("rate"));
                }
            }
        } catch (SQLException e) {
            log.warn("Could not read stored rate history: {}", e.getMessage());
        }
        return history;
    }

    /** The most recently stored rate per currency, whatever date it came from. */
    public Map<String, BigDecimal> latest() {
        String sql = """
                SELECT DISTINCT ON (base) base, rate
                  FROM fx_rate
                 WHERE quote = 'NOK'
                 ORDER BY base, as_of DESC
                """;
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rates.put(rs.getString("base"), rs.getBigDecimal("rate"));
            }
        } catch (SQLException e) {
            log.warn("Could not read cached rates: {}", e.getMessage());
        }
        return rates;
    }
}
