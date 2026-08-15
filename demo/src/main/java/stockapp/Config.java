package stockapp;

import io.github.cdimascio.dotenv.Dotenv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Application configuration, resolved once at startup.
 *
 * <p>Values are read in this order, first hit wins:
 * <ol>
 *   <li>JVM system properties ({@code -Dserver.port=9000}) - handy for tests</li>
 *   <li>OS environment variables</li>
 *   <li>the nearest {@code .env} file at or above the working directory</li>
 *   <li>the built-in default</li>
 * </ol>
 *
 * <p>The {@code .env} lookup walks up from the working directory instead of
 * hard-coding {@code "../"}, so the app behaves identically whether it is
 * started from the repository root, from {@code demo/}, or from a packaged jar
 * somewhere else entirely.
 */
public final class Config {

    private static final Dotenv DOTENV = loadDotenv();

    // --- Database -----------------------------------------------------------
    public static final String DB_URL = get("DB_URL", "jdbc:postgresql://localhost:5433/postgres");
    public static final String DB_USERNAME = get("DB_USERNAME", "postgres");
    public static final String DB_PASSWORD = get("DB_PASSWORD", "");
    public static final int DB_POOL_SIZE = getInt("DB_POOL_SIZE", 8);

    // --- Alpaca -------------------------------------------------------------
    /** Trading API: account, assets, market clock. */
    public static final String API_URL = trimTrailingSlash(get("API_URL", "https://paper-api.alpaca.markets"));
    /** Market data API: bars, snapshots. Separate host from the trading API. */
    public static final String DATA_URL = trimTrailingSlash(get("DATA_URL", "https://data.alpaca.markets"));
    public static final String API_KEY_ID = get("API_KEY_ID", "");
    public static final String API_SECRET_KEY = get("API_SECRET_KEY", "");

    /**
     * Market data feed. "sip" is the full consolidated tape and requires a paid
     * data subscription; "iex" is the free single-exchange feed. Defaults to
     * sip with an automatic fallback to iex if the account is not entitled.
     */
    public static final String DATA_FEED = get("DATA_FEED", "sip");

    // --- eToro --------------------------------------------------------------
    /**
     * Static keys from Settings &gt; Trading &gt; API Key Management. Read
     * permission is enough; this app never places an order through eToro.
     * Optional - the feature hides itself when they are absent.
     */
    public static final String ETORO_API_KEY = get("ETORO_API_KEY", "");
    public static final String ETORO_USER_KEY = get("ETORO_USER_KEY", "");
    /** Demo and Real are separate environments with separate keys. */
    public static final boolean ETORO_DEMO = getBool("ETORO_DEMO", false);

    // --- Server -------------------------------------------------------------
    public static final int SERVER_PORT = getInt("SERVER_PORT", 4567);

    // --- Behaviour ----------------------------------------------------------
    /** Virtual starting cash for the local paper portfolio. */
    public static final String PAPER_STARTING_CASH = get("PAPER_STARTING_CASH", "100000");
    /** Seconds a quote/candle response is reused before Alpaca is called again. */
    public static final int QUOTE_CACHE_SECONDS = getInt("QUOTE_CACHE_SECONDS", 15);
    public static final int CANDLE_CACHE_SECONDS = getInt("CANDLE_CACHE_SECONDS", 60);
    /** Set false to skip the (slow) full asset-universe refresh on boot. */
    public static final boolean SYNC_ASSETS_ON_START = getBool("SYNC_ASSETS_ON_START", false);

    private Config() {
    }

    /**
     * Fails fast with an actionable message when credentials are missing, and
     * prints a startup summary that never contains a secret.
     */
    public static void validate() {
        if (API_KEY_ID.isBlank() || API_SECRET_KEY.isBlank()) {
            throw new IllegalStateException("""
                    Alpaca credentials are missing.

                    Create a .env file in the project root (copy .env.example) and set:
                      API_KEY_ID=<your key id>
                      API_SECRET_KEY=<your secret key>

                    Keys come from https://app.alpaca.markets/paper/dashboard/overview
                    """);
        }
        if (DB_PASSWORD.isBlank()) {
            System.out.println("[config] WARNING: DB_PASSWORD is empty; the database connection will likely fail.");
        }
    }

    public static String summary() {
        return """
                [config] database : %s (user %s, pool %d)
                [config] trading  : %s
                [config] data     : %s (feed %s)
                [config] key id   : %s
                [config] port     : %d"""
                .formatted(DB_URL, DB_USERNAME, DB_POOL_SIZE, API_URL, DATA_URL, DATA_FEED, maskedKeyId(), SERVER_PORT);
    }

    private static String maskedKeyId() {
        if (API_KEY_ID.isBlank()) {
            return "NOT SET";
        }
        int keep = Math.min(4, API_KEY_ID.length());
        return "*".repeat(Math.max(0, API_KEY_ID.length() - keep)) + API_KEY_ID.substring(API_KEY_ID.length() - keep);
    }

    // --- Lookup -------------------------------------------------------------

    static String get(String key, String defaultValue) {
        String fromSystemProperty = System.getProperty(toPropertyKey(key));
        if (isSet(fromSystemProperty)) {
            return fromSystemProperty;
        }
        String fromEnv = System.getenv(key);
        if (isSet(fromEnv)) {
            return fromEnv;
        }
        String fromDotenv = DOTENV == null ? null : DOTENV.get(key);
        return isSet(fromDotenv) ? fromDotenv : defaultValue;
    }

    static int getInt(String key, int defaultValue) {
        String raw = get(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            System.out.printf("[config] WARNING: %s=\"%s\" is not a number; using %d.%n", key, raw, defaultValue);
            return defaultValue;
        }
    }

    static boolean getBool(String key, boolean defaultValue) {
        String raw = get(key, String.valueOf(defaultValue)).trim();
        return raw.equalsIgnoreCase("true") || raw.equals("1") || raw.equalsIgnoreCase("yes");
    }

    /** DB_POOL_SIZE -> db.pool.size */
    private static String toPropertyKey(String envKey) {
        return envKey.toLowerCase().replace('_', '.');
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Walks up from the working directory looking for a .env file. */
    private static Dotenv loadDotenv() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int depth = 0; dir != null && depth < 5; depth++, dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(".env"))) {
                return Dotenv.configure()
                        .directory(dir.toString())
                        .ignoreIfMalformed()
                        .ignoreIfMissing()
                        .load();
            }
        }
        return Dotenv.configure().ignoreIfMissing().load();
    }
}
