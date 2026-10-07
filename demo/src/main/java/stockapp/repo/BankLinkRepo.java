package stockapp.repo;

import stockapp.Db;
import stockapp.enablebanking.EnableBankingClient;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bank consents given through Enable Banking, and the accounts each one covers. */
public final class BankLinkRepo {

    /** One consent at one bank. */
    public record Link(int id, String bank, String country, String sessionId, Instant validUntil,
                       List<EnableBankingClient.LinkedAccount> accounts) {

        public boolean expired(Instant now) {
            return !validUntil.isAfter(now);
        }
    }

    private final Db db;

    public BankLinkRepo(Db db) {
        this.db = db;
    }

    /**
     * Stores a new consent and its accounts in one transaction. It replaces any
     * earlier consent for the same bank: renewing a link must not leave two
     * sessions syncing the same accounts.
     */
    public int save(String bank, String country, EnableBankingClient.Session session) {
        try (Connection conn = db.connection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM bank_link WHERE bank_name = ?")) {
                    ps.setString(1, bank);
                    ps.executeUpdate();
                }
                int id;
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO bank_link (bank_name, country, session_id, valid_until)
                        VALUES (?, ?, ?, ?) RETURNING id
                        """)) {
                    ps.setString(1, bank);
                    ps.setString(2, country);
                    ps.setString(3, session.id());
                    ps.setTimestamp(4, Timestamp.from(session.validUntil()));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        id = rs.getInt("id");
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO bank_link_account (link_id, account_uid, name, identifier, currency)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
                    for (EnableBankingClient.LinkedAccount account : session.accounts()) {
                        ps.setInt(1, id);
                        ps.setString(2, account.uid());
                        ps.setString(3, account.name());
                        ps.setString(4, account.identifier());
                        ps.setString(5, account.currency());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
                return id;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store the bank link for " + bank, e);
        }
    }

    /** Every stored consent with its accounts, newest first. */
    public List<Link> all() {
        String sql = """
                SELECT l.id, l.bank_name, l.country, l.session_id, l.valid_until,
                       a.account_uid, a.name, a.identifier, a.currency
                  FROM bank_link l
                  LEFT JOIN bank_link_account a ON a.link_id = l.id
                 ORDER BY l.created_at DESC, l.id DESC, a.account_uid
                """;
        Map<Integer, Link> links = new LinkedHashMap<>();
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Link link = links.computeIfAbsent(rs.getInt("id"), id -> {
                    try {
                        return new Link(id, rs.getString("bank_name"), rs.getString("country"),
                                rs.getString("session_id"), rs.getTimestamp("valid_until").toInstant(),
                                new ArrayList<>());
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                });
                if (rs.getString("account_uid") != null) {
                    link.accounts().add(new EnableBankingClient.LinkedAccount(rs.getString("account_uid"),
                            rs.getString("name"), rs.getString("identifier"), rs.getString("currency")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read bank links", e);
        }
        return new ArrayList<>(links.values());
    }
}
