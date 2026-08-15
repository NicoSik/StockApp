package stockapp.repo;

import stockapp.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Instruments and the per-broker aliases that point at them.
 *
 * <p>The alias table is what makes the reconcile work a one-off. Nordnet calls
 * a holding "Oscar Health A" and DNB would call it "OSCR"; once either is
 * mapped, that broker's spelling resolves instantly forever after, with no
 * further searching and no chance of the match drifting.
 */
public final class InstrumentRepo {

    private final Db db;

    public InstrumentRepo(Db db) {
        this.db = db;
    }

    public record Instrument(int id,
                             String isin,
                             String symbol,
                             String name,
                             String currency,
                             String kind,
                             String priceSource,
                             boolean verified) {
    }

    private static final String SELECT =
            "SELECT id, isin, symbol, name, currency, kind, price_source, verified FROM instrument ";

    public Optional<Instrument> findById(int id) {
        return one(SELECT + "WHERE id = ?", ps -> ps.setInt(1, id));
    }

    public Optional<Instrument> findBySymbol(String symbol) {
        return one(SELECT + "WHERE upper(symbol) = upper(?)", ps -> ps.setString(1, symbol));
    }

    /** The instrument a broker's own label maps to, if it has been mapped. */
    public Optional<Instrument> findByAlias(String broker, String alias) {
        String sql = """
                SELECT i.id, i.isin, i.symbol, i.name, i.currency, i.kind, i.price_source, i.verified
                  FROM instrument i
                  JOIN instrument_alias a ON a.instrument_id = i.id
                 WHERE a.broker = ? AND a.alias = ?
                """;
        return one(sql, ps -> {
            ps.setString(1, broker);
            ps.setString(2, alias);
        });
    }

    public List<Instrument> listAll() {
        List<Instrument> instruments = new ArrayList<>();
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(SELECT + "ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                instruments.add(read(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not list instruments", e);
        }
        return instruments;
    }

    /**
     * Creates or updates an instrument, keyed by symbol where there is one.
     *
     * <p>{@code verified} is only ever raised, never lowered: once a human has
     * confirmed a mapping, a later automatic pass must not quietly un-confirm it.
     */
    public Instrument upsert(String symbol, String name, String currency,
                             String kind, String priceSource, boolean verified) {
        String sql = """
                INSERT INTO instrument (symbol, name, currency, kind, price_source, verified, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                RETURNING id
                """;
        // Symbol is intentionally not unique - a manually tracked fund has no
        // symbol at all, and several such rows must be able to coexist. So the
        // "update if it exists" half is an explicit look-up rather than an
        // ON CONFLICT clause.
        if (symbol != null && !symbol.isBlank()) {
            Optional<Instrument> existing = findBySymbol(symbol);
            if (existing.isPresent()) {
                update(existing.get().id(), name, currency, kind, priceSource, verified);
                return findById(existing.get().id()).orElseThrow();
            }
        }
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, symbol);
            ps.setString(2, name);
            ps.setString(3, currency == null ? "NOK" : currency);
            ps.setString(4, kind == null ? "STOCK" : kind);
            ps.setString(5, priceSource == null ? "NONE" : priceSource);
            ps.setBoolean(6, verified);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return findById(rs.getInt("id")).orElseThrow();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not save instrument " + name + ": " + e.getMessage(), e);
        }
    }

    private void update(int id, String name, String currency, String kind, String priceSource, boolean verified) {
        String sql = """
                UPDATE instrument
                   SET name = COALESCE(?, name),
                       currency = COALESCE(?, currency),
                       kind = COALESCE(?, kind),
                       price_source = COALESCE(?, price_source),
                       verified = verified OR ?,
                       updated_at = now()
                 WHERE id = ?
                """;
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, currency);
            ps.setString(3, kind);
            ps.setString(4, priceSource);
            ps.setBoolean(5, verified);
            ps.setInt(6, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not update instrument " + id, e);
        }
    }

    /**
     * Creates or updates an instrument keyed by a broker's own identifier.
     *
     * <p>eToro identifies instruments by an opaque numeric id rather than a
     * ticker, and several of its holdings - copy portfolios, leveraged CFDs -
     * have no ticker at all. Keying on (source, external id) is the only stable
     * identity available for those, and it survives eToro later supplying a
     * name where it previously did not.
     */
    public Instrument upsertExternal(String source, String externalId, String symbol,
                                     String name, String currency, String kind, String priceSource) {
        String sql = """
                INSERT INTO instrument (external_source, external_id, symbol, name, currency,
                                        kind, price_source, verified, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, now())
                ON CONFLICT (external_source, external_id) DO UPDATE
                    SET symbol       = COALESCE(EXCLUDED.symbol, instrument.symbol),
                        name         = COALESCE(EXCLUDED.name, instrument.name),
                        currency     = COALESCE(EXCLUDED.currency, instrument.currency),
                        kind         = EXCLUDED.kind,
                        price_source = EXCLUDED.price_source,
                        updated_at   = now()
                RETURNING id
                """;
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, source);
            ps.setString(2, externalId);
            ps.setString(3, symbol);
            ps.setString(4, name);
            ps.setString(5, currency);
            ps.setString(6, kind == null ? "OTHER" : kind);
            ps.setString(7, priceSource == null ? "NONE" : priceSource);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return findById(rs.getInt("id")).orElseThrow();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not save " + source + " instrument " + externalId + ": " + e.getMessage(), e);
        }
    }

    /** Remembers that {@code alias} from {@code broker} means this instrument. */
    public void linkAlias(String broker, String alias, int instrumentId) {
        String sql = """
                INSERT INTO instrument_alias (broker, alias, instrument_id)
                VALUES (?, ?, ?)
                ON CONFLICT (broker, alias) DO UPDATE SET instrument_id = EXCLUDED.instrument_id
                """;
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, broker);
            ps.setString(2, alias);
            ps.setInt(3, instrumentId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not link alias " + alias, e);
        }
    }

    // ---------------------------------------------------------------- helpers

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private Optional<Instrument> one(String sql, Binder binder) {
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Instrument lookup failed: " + e.getMessage(), e);
        }
    }

    private static Instrument read(ResultSet rs) throws SQLException {
        return new Instrument(
                rs.getInt("id"),
                rs.getString("isin"),
                rs.getString("symbol"),
                rs.getString("name"),
                rs.getString("currency"),
                rs.getString("kind"),
                rs.getString("price_source"),
                rs.getBoolean("verified"));
    }
}
