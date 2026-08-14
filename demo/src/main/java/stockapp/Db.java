package stockapp;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the connection pool and the schema migrations.
 *
 * <p>The previous implementation opened one {@link Connection} at startup and
 * shared it with every HTTP handler. {@code java.sql.Connection} is not
 * thread-safe and Javalin serves requests on a thread pool, so concurrent
 * requests could interleave on the same connection and corrupt each other's
 * results. Every query now borrows a connection from the pool and returns it.
 */
public final class Db implements AutoCloseable {

    /**
     * Migrations are listed explicitly rather than discovered by scanning the
     * classpath, because directory listing does not work inside a shaded jar.
     * Append new files here; never edit one that has already shipped.
     */
    private static final String[] MIGRATIONS = {
            "V001__baseline.sql",
            "V002__stock_price_ohlcv.sql",
            "V003__watchlists.sql",
            "V004__paper_portfolio.sql",
            "V005__price_alerts.sql",
            "V006__search_indexes.sql",
            "V007__aggregator.sql",
    };

    private final HikariDataSource dataSource;

    public Db() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(Config.DB_URL);
        config.setUsername(Config.DB_USERNAME);
        config.setPassword(Config.DB_PASSWORD);
        config.setMaximumPoolSize(Config.DB_POOL_SIZE);
        config.setMinimumIdle(1);
        config.setPoolName("ticker-pool");
        config.setConnectionTimeout(10_000);
        // Surface a dead database at startup rather than on the first request.
        config.setInitializationFailTimeout(10_000);
        this.dataSource = new HikariDataSource(config);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Applies any migration that has not run yet, each in its own transaction,
     * and records it in {@code schema_migration}.
     */
    public void migrate() {
        try (Connection conn = connection()) {
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS schema_migration (
                            filename   TEXT PRIMARY KEY,
                            applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )
                        """);
            }

            List<String> applied = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT filename FROM schema_migration")) {
                while (rs.next()) {
                    applied.add(rs.getString(1));
                }
            }

            int ran = 0;
            for (String filename : MIGRATIONS) {
                if (applied.contains(filename)) {
                    continue;
                }
                String sql = readMigration(filename);
                conn.setAutoCommit(false);
                try {
                    try (Statement st = conn.createStatement()) {
                        st.execute(sql);
                    }
                    try (PreparedStatement ps =
                                 conn.prepareStatement("INSERT INTO schema_migration (filename) VALUES (?)")) {
                        ps.setString(1, filename);
                        ps.executeUpdate();
                    }
                    conn.commit();
                    System.out.println("[db] applied migration " + filename);
                    ran++;
                } catch (SQLException e) {
                    conn.rollback();
                    throw new IllegalStateException("Migration " + filename + " failed: " + e.getMessage(), e);
                } finally {
                    conn.setAutoCommit(true);
                }
            }
            System.out.printf("[db] schema up to date (%d migration(s) applied this run)%n", ran);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not run database migrations: " + e.getMessage(), e);
        }
    }

    private String readMigration(String filename) {
        String resource = "/db/migration/" + filename;
        try (InputStream in = Db.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Migration resource not found on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read migration " + resource, e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
