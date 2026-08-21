package stockapp.repo;

import stockapp.Db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Accounts and their dated snapshots.
 *
 * <p>Nothing here mutates a holding. An import writes a whole new snapshot, so
 * re-importing the same day replaces that day cleanly, an undo is a delete, and
 * the value-over-time history accumulates for free - which matters, because
 * neither broker exports transactions this app could rebuild history from.
 */
public final class AccountRepo {

    private final Db db;

    public AccountRepo(Db db) {
        this.db = db;
    }

    /**
     * @param simulated true when the money is not real - an eToro demo account.
     *                  Such accounts are shown but never counted in a total.
     */
    public record Account(int id, String name, String broker, String kind, String currency,
                          boolean simulated) {
    }

    public record Snapshot(int id, int accountId, LocalDate asOf, String sourceFile,
                           BigDecimal reportedTotalNok, BigDecimal reportedCostBasisNok) {
    }

    /**
     * A stored holding joined to the instrument it refers to.
     *
     * @param leverage  null for an ordinary holding, above 1 for a CFD
     * @param direction LONG or SHORT, null when the concept does not apply
     */
    public record StoredHolding(int instrumentId,
                                String symbol,
                                String name,
                                String currency,
                                String kind,
                                String priceSource,
                                boolean verified,
                                BigDecimal quantity,
                                BigDecimal avgCost,
                                BigDecimal valueNok,
                                BigDecimal leverage,
                                String direction) {

        /** Convenience for the file importers, which have neither concept. */
        public StoredHolding(int instrumentId, String symbol, String name, String currency,
                             String kind, String priceSource, boolean verified,
                             BigDecimal quantity, BigDecimal avgCost, BigDecimal valueNok) {
            this(instrumentId, symbol, name, currency, kind, priceSource, verified,
                    quantity, avgCost, valueNok, null, null);
        }
    }

    // --------------------------------------------------------------- accounts

    public List<Account> listAccounts() {
        List<Account> accounts = new ArrayList<>();
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, name, broker, kind, currency, simulated FROM account ORDER BY sort_order, id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                accounts.add(new Account(rs.getInt("id"), rs.getString("name"),
                        rs.getString("broker"), rs.getString("kind"), rs.getString("currency"),
                        rs.getBoolean("simulated")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not list accounts", e);
        }
        return accounts;
    }

    public Optional<Account> findAccount(int id) {
        return listAccounts().stream().filter(a -> a.id() == id).findFirst();
    }

    /** Returns the account for a broker, creating it on first import. */
    public Account ensureAccount(String name, String broker, String kind) {
        return ensureAccount(name, broker, kind, false);
    }

    /**
     * @param simulated marks an account whose money is not real, so its value is
     *                  displayed but excluded from any total
     */
    public Account ensureAccount(String name, String broker, String kind, boolean simulated) {
        String insert = """
                INSERT INTO account (name, broker, kind, simulated)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (name) DO UPDATE SET simulated = EXCLUDED.simulated
                """;
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setString(1, name);
            ps.setString(2, broker);
            ps.setString(3, kind);
            ps.setBoolean(4, simulated);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create account " + name, e);
        }
        return listAccounts().stream()
                .filter(a -> a.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Account " + name + " vanished after insert"));
    }

    // -------------------------------------------------------------- snapshots

    /**
     * Replaces any snapshot already stored for this account and date, then
     * writes the holdings, all in one transaction.
     */
    public int writeSnapshot(int accountId, LocalDate asOf, String sourceFile,
                             BigDecimal reportedTotalNok, List<StoredHolding> holdings) {
        return writeSnapshot(accountId, asOf, sourceFile, reportedTotalNok, null, holdings);
    }

    /**
     * @param reportedCostBasisNok the cost basis the broker stated for the
     *                             account as a whole, where no holding carries
     *                             one of its own. DNB's report is like that.
     */
    public int writeSnapshot(int accountId, LocalDate asOf, String sourceFile,
                             BigDecimal reportedTotalNok, BigDecimal reportedCostBasisNok,
                             List<StoredHolding> holdings) {
        try (Connection conn = db.connection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM snapshot WHERE account_id = ? AND as_of = ?")) {
                    ps.setInt(1, accountId);
                    ps.setDate(2, Date.valueOf(asOf));
                    ps.executeUpdate();
                }

                int snapshotId;
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO snapshot (account_id, as_of, source_file, reported_total_nok, reported_cost_basis_nok)
                        VALUES (?, ?, ?, ?, ?)
                        RETURNING id
                        """)) {
                    ps.setInt(1, accountId);
                    ps.setDate(2, Date.valueOf(asOf));
                    ps.setString(3, sourceFile);
                    ps.setBigDecimal(4, reportedTotalNok);
                    ps.setBigDecimal(5, reportedCostBasisNok);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        snapshotId = rs.getInt("id");
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO holding (snapshot_id, instrument_id, quantity, avg_cost, currency,
                                             value_native, value_nok, leverage, direction)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (snapshot_id, instrument_id) DO UPDATE
                            SET quantity = holding.quantity + EXCLUDED.quantity,
                                value_nok = holding.value_nok + EXCLUDED.value_nok
                        """)) {
                    for (StoredHolding holding : holdings) {
                        ps.setInt(1, snapshotId);
                        ps.setInt(2, holding.instrumentId());
                        ps.setBigDecimal(3, holding.quantity());
                        ps.setBigDecimal(4, holding.avgCost());
                        ps.setString(5, holding.currency());
                        ps.setBigDecimal(6, null);
                        ps.setBigDecimal(7, holding.valueNok());
                        ps.setBigDecimal(8, holding.leverage());
                        ps.setString(9, holding.direction());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                conn.commit();
                return snapshotId;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store the snapshot: " + e.getMessage(), e);
        }
    }

    public Optional<Snapshot> latestSnapshot(int accountId) {
        String sql = """
                SELECT id, account_id, as_of, source_file, reported_total_nok, reported_cost_basis_nok
                  FROM snapshot WHERE account_id = ?
                 ORDER BY as_of DESC, id DESC LIMIT 1
                """;
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Snapshot(rs.getInt("id"), rs.getInt("account_id"),
                        rs.getDate("as_of").toLocalDate(), rs.getString("source_file"),
                        rs.getBigDecimal("reported_total_nok"),
                        rs.getBigDecimal("reported_cost_basis_nok")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the latest snapshot", e);
        }
    }

    public List<StoredHolding> holdings(int snapshotId) {
        String sql = """
                SELECT h.instrument_id, i.symbol, i.name, i.kind, i.price_source, i.verified,
                       h.quantity, h.avg_cost, h.currency, h.value_nok, h.leverage, h.direction
                  FROM holding h
                  JOIN instrument i ON i.id = h.instrument_id
                 WHERE h.snapshot_id = ?
                 ORDER BY h.value_nok DESC
                """;
        List<StoredHolding> holdings = new ArrayList<>();
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, snapshotId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    holdings.add(new StoredHolding(
                            rs.getInt("instrument_id"),
                            rs.getString("symbol"),
                            rs.getString("name"),
                            rs.getString("currency"),
                            rs.getString("kind"),
                            rs.getString("price_source"),
                            rs.getBoolean("verified"),
                            rs.getBigDecimal("quantity"),
                            rs.getBigDecimal("avg_cost"),
                            rs.getBigDecimal("value_nok"),
                            rs.getBigDecimal("leverage"),
                            rs.getString("direction")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read holdings", e);
        }
        return holdings;
    }

    /**
     * Combined value per date, for the value-over-time chart.
     *
     * <p>Each point sums every account's most recent snapshot <em>on or before</em>
     * that date, rather than only the snapshots written on it. Grouping by date
     * alone plotted whichever accounts happened to be imported that day: a day
     * when only DNB was re-imported showed DNB's value as the whole portfolio,
     * and the first eToro sync made the line fall from six figures to five while
     * the money it omitted was still sitting there. Every account a person still
     * holds is carried forward until it is imported again.
     *
     * <p>Simulated accounts are excluded here as everywhere else - practice
     * money has no business in a net-worth history.
     *
     * <p>These are the values the brokers reported at import, not live prices,
     * so the last point is a snapshot total and will not exactly equal today's
     * live headline figure.
     */
    public List<Object[]> valueHistory() {
        String sql = """
                WITH snapshot_value AS (
                    SELECT s.id, s.account_id, s.as_of, COALESCE(sum(h.value_nok), 0) AS value
                      FROM snapshot s
                      JOIN account a ON a.id = s.account_id AND a.simulated = false
                      LEFT JOIN holding h ON h.snapshot_id = s.id
                     GROUP BY s.id, s.account_id, s.as_of
                ),
                points AS (SELECT DISTINCT as_of FROM snapshot_value),
                carried AS (
                    SELECT p.as_of AS point_date, sv.value,
                           row_number() OVER (PARTITION BY p.as_of, sv.account_id
                                              ORDER BY sv.as_of DESC, sv.id DESC) AS rn
                      FROM points p
                      JOIN snapshot_value sv ON sv.as_of <= p.as_of
                )
                SELECT point_date AS as_of, sum(value) AS total
                  FROM carried
                 WHERE rn = 1
                 GROUP BY point_date
                 ORDER BY point_date
                """;
        List<Object[]> points = new ArrayList<>();
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                points.add(new Object[] {rs.getDate("as_of").toLocalDate(), rs.getBigDecimal("total")});
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read value history", e);
        }
        return points;
    }
}
