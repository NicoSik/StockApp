package stockapp.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.UploadedFile;
import stockapp.Config;
import stockapp.enablebanking.EnableBankingException;
import stockapp.etoro.EtoroClient;
import stockapp.etoro.EtoroException;
import stockapp.importer.ImportException;
import stockapp.repo.AccountRepo;
import stockapp.repo.InstrumentRepo;
import stockapp.service.BankSyncService;
import stockapp.service.EtoroSyncService;
import stockapp.service.HoldingsHistorySync;
import stockapp.service.ImportService;
import stockapp.service.ValuationService;
import stockapp.yahoo.YahooClient;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints for the multi-broker aggregator.
 *
 * <p>Deliberately mounted under {@code /api/holdings} rather than mixed into
 * {@code /api/portfolio}, which remains the simulated paper portfolio. The two
 * are separate everywhere: separate tables, separate endpoints, separate screen.
 */
public final class AggregatorApi {

    /** Broker exports are small; anything larger is a mistake or an attack. */
    private static final long MAX_UPLOAD_BYTES = 8L * 1024 * 1024;

    private final AccountRepo accounts;
    private final InstrumentRepo instruments;
    private final ImportService imports;
    private final ValuationService valuation;
    private final HoldingsHistorySync historySync;
    private final YahooClient yahoo;
    private final EtoroSyncService etoro;
    private final EtoroClient etoroClient;
    private final BankSyncService bankSync;

    public AggregatorApi(AccountRepo accounts,
                         InstrumentRepo instruments,
                         ImportService imports,
                         ValuationService valuation,
                         HoldingsHistorySync historySync,
                         YahooClient yahoo,
                         EtoroSyncService etoro,
                         EtoroClient etoroClient,
                         BankSyncService bankSync) {
        this.accounts = accounts;
        this.instruments = instruments;
        this.imports = imports;
        this.valuation = valuation;
        this.historySync = historySync;
        this.yahoo = yahoo;
        this.etoro = etoro;
        this.etoroClient = etoroClient;
        this.bankSync = bankSync;
    }

    public void register(RoutesConfig routes) {
        routes.get("/api/holdings", ctx -> ctx.json(valuation.valueEverything()));
        routes.get("/api/holdings/history", ctx -> ctx.json(Map.of("points", valuation.history())));
        routes.get("/api/holdings/accounts", ctx -> ctx.json(accounts.listAccounts()));
        routes.get("/api/holdings/instruments", ctx -> ctx.json(instruments.listAll()));

        routes.post("/api/holdings/import/preview", this::previewImport);
        routes.post("/api/holdings/funds/preview", this::previewFunds);
        routes.post("/api/holdings/import/commit", this::commitImport);

        routes.get("/api/holdings/etoro/status", this::etoroStatus);
        routes.post("/api/holdings/etoro/sync", this::etoroSync);
        routes.get("/api/holdings/etoro/raw", this::etoroRaw);

        // Backs the reconcile screen: lets the user search for the right symbol
        // when the automatic match was refused.
        routes.get("/api/holdings/lookup", this::lookup);

        // Bank balances through Enable Banking: link with BankID, then sync.
        routes.get("/api/holdings/banks", this::bankStatus);
        routes.get("/api/holdings/banks/available", ctx -> ctx.json(requireBanks().banks()));
        routes.post("/api/holdings/banks/connect", this::connectBank);
        routes.get("/api/holdings/banks/callback", this::bankCallback);
        routes.post("/api/holdings/banks/complete", this::completeBank);
        routes.post("/api/holdings/banks/sync", this::syncBanks);
    }

    // ---------------------------------------------------------------- import

    private void previewImport(Context ctx) {
        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) {
            throw new BadRequest("No file was uploaded.");
        }
        if (file.size() > MAX_UPLOAD_BYTES) {
            throw new BadRequest("That file is larger than 8 MB; broker exports are a few kilobytes.");
        }

        byte[] content;
        try (InputStream in = file.content()) {
            content = in.readAllBytes();
        } catch (IOException e) {
            throw new ImportException("The upload could not be read.", e);
        }
        ctx.json(imports.preview(file.filename(), content));
    }

    /**
     * Resolves hand-entered funds and returns an ordinary import preview, so
     * the browser confirms and commits them through the existing path.
     *
     * <p>The whole account is sent every time, not one new fund, because a
     * snapshot is the complete state of an account on a date. Accepting a
     * single addition would write a snapshot containing only that fund and
     * silently drop the rest.
     */
    private void previewFunds(Context ctx) {
        JsonObject body = Json.parseObject(ctx.body());
        String accountName = Json.requireString(body, "accountName");
        String broker = Json.requireString(body, "broker");

        if (!body.has("funds") || !body.get("funds").isJsonArray()) {
            throw new BadRequest("\"funds\" must be an array.");
        }
        List<ImportService.ManualFund> funds = new ArrayList<>();
        for (JsonElement element : body.getAsJsonArray("funds")) {
            if (!element.isJsonObject()) {
                throw new BadRequest("Each fund must be an object.");
            }
            JsonObject fund = element.getAsJsonObject();
            funds.add(new ImportService.ManualFund(
                    text(fund, "name"),
                    text(fund, "isin"),
                    decimal(fund, "units"),
                    decimal(fund, "valueNok"),
                    decimal(fund, "costBasisNok")));
        }
        ctx.json(imports.previewFunds(accountName, broker, funds));
    }

    private static String text(JsonObject json, String field) {
        return json.has(field) && !json.get(field).isJsonNull()
                ? json.get(field).getAsString() : null;
    }

    /** Blank means "not given", which is different from zero. */
    private static BigDecimal decimal(JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            return null;
        }
        String raw = json.get(field).getAsString().trim().replace(",", ".").replace(" ", "");
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new BadRequest("\"" + field + "\" is not a number: " + raw);
        }
    }

    private void commitImport(Context ctx) {
        JsonObject body = Json.parseObject(ctx.body());
        String previewId = Json.requireString(body, "previewId");

        // { "12": "EQNR.OL" } - rows the user re-mapped by hand.
        Map<Integer, String> overrides = new HashMap<>();
        if (body.has("overrides") && body.get("overrides").isJsonObject()) {
            JsonObject raw = body.getAsJsonObject("overrides");
            for (String key : raw.keySet()) {
                try {
                    overrides.put(Integer.parseInt(key), raw.get(key).getAsString());
                } catch (NumberFormatException | UnsupportedOperationException e) {
                    throw new BadRequest("\"overrides\" keys must be row numbers.");
                }
            }
        }

        List<Integer> skip = new ArrayList<>();
        if (body.has("skip") && body.get("skip").isJsonArray()) {
            JsonArray raw = body.getAsJsonArray("skip");
            for (JsonElement element : raw) {
                try {
                    skip.add(element.getAsInt());
                } catch (RuntimeException e) {
                    throw new BadRequest("\"skip\" must be an array of row numbers.");
                }
            }
        }

        ImportService.Result result = imports.commit(previewId, overrides, skip);
        // A new snapshot can bring symbols the history has no closes for yet.
        historySync.refreshInBackground();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", result);
        response.put("holdings", valuation.valueEverything());
        ctx.status(HttpStatus.CREATED).json(response);
    }

    // ----------------------------------------------------------------- etoro

    private void etoroStatus(Context ctx) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configured", etoro.configured());
        status.put("demo", Config.ETORO_DEMO);
        ctx.json(status);
    }

    private void etoroSync(Context ctx) {
        if (!etoro.configured()) {
            throw new EtoroException(
                    "eToro is not configured. Add ETORO_API_KEY and ETORO_USER_KEY to .env, then restart. "
                            + "Generate them in eToro under Settings > Trading > API Key Management "
                            + "with Read permission.");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", etoro.sync());
        historySync.refreshInBackground();
        response.put("holdings", valuation.valueEverything());
        ctx.status(HttpStatus.CREATED).json(response);
    }

    /**
     * Returns an eToro response untouched.
     *
     * <p>Everything here was written against documentation rather than against
     * a live account, so when a field lands somewhere unexpected this is what
     * shows the real shape without guessing. Read-only and limited to eToro's
     * own host.
     */
    private void etoroRaw(Context ctx) {
        String path = ctx.queryParam("path");
        if (path == null || path.isBlank()) {
            path = "/trading/info/" + (Config.ETORO_DEMO ? "demo" : "real") + "/aggregate-portfolio";
        }
        if (path.contains("://") || path.contains("..")) {
            throw new BadRequest("\"path\" must be a path on the eToro API, not a URL.");
        }
        ctx.contentType("application/json").result(etoroClient.raw(path));
    }

    // ---------------------------------------------------------------- lookup

    // ----------------------------------------------------------------- banks

    private void bankStatus(Context ctx) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configured", bankSync.configured());
        status.put("redirectUrl", bankSync.redirectUrl());
        status.put("links", bankSync.links());
        ctx.json(status);
    }

    private void connectBank(Context ctx) {
        String bank = Json.requireString(Json.parseObject(ctx.body()), "bank");
        ctx.json(Map.of("url", requireBanks().connect(bank)));
    }

    /**
     * Where the bank sends the browser after BankID, when the registered
     * callback is this app. A browser lands here, not a fetch, so the answer is
     * a small page rather than JSON.
     */
    private void bankCallback(Context ctx) {
        String title;
        String message;
        try {
            BankSyncService.Result result = requireBanks().completeFromUrl(ctx.fullUrl());
            historySync.refreshInBackground();
            title = result.bank() + " is linked";
            message = result.accounts() + " account(s) added to your holdings as " + result.accountName() + ".";
        } catch (EnableBankingException e) {
            title = "The bank was not linked";
            message = e.getMessage();
        }
        ctx.contentType("text/html; charset=utf-8").result("""
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="color-scheme" content="light dark">
                <title>Ticker</title></head>
                <body style="font-family:system-ui,sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem">
                <h1 style="font-size:1.4rem">%s</h1><p>%s</p>
                <p><a href="/holdings">Back to holdings</a></p></body></html>
                """.formatted(escape(title), escape(message)));
    }

    /** The fallback when the bank sends the browser somewhere else: the user pastes that address. */
    private void completeBank(Context ctx) {
        String url = Json.requireString(Json.parseObject(ctx.body()), "url");
        BankSyncService.Result result = requireBanks().completeFromUrl(url);
        historySync.refreshInBackground();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", result);
        response.put("holdings", valuation.valueEverything());
        ctx.status(HttpStatus.CREATED).json(response);
    }

    private void syncBanks(Context ctx) {
        List<BankSyncService.Result> results = requireBanks().syncAll();
        historySync.refreshInBackground();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", results);
        response.put("holdings", valuation.valueEverything());
        ctx.status(HttpStatus.CREATED).json(response);
    }

    private BankSyncService requireBanks() {
        if (!bankSync.configured()) {
            throw new BadRequest("Bank balances are not configured. Set ENABLE_BANKING_APP_ID and "
                    + "ENABLE_BANKING_KEY_FILE in .env, then restart. See the README, Bank balances.");
        }
        return bankSync;
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private void lookup(Context ctx) {
        String query = ctx.queryParam("q");
        if (query == null || query.isBlank()) {
            ctx.json(List.of());
            return;
        }
        String currency = ctx.queryParam("currency");
        String suffix = YahooClient.suffixForCurrency(currency);

        List<Map<String, Object>> results = new ArrayList<>();
        for (YahooClient.Match match : yahoo.search(query)) {
            // When the currency is known, only offer listings on that market -
            // the whole point is to stop a Danish share class standing in for a
            // Norwegian one.
            if (!suffix.isEmpty() && !match.symbol().endsWith(suffix)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", match.symbol());
            row.put("name", match.name());
            row.put("exchange", match.exchange());
            row.put("type", match.quoteType());
            yahoo.quote(match.symbol()).ifPresent(quote -> {
                row.put("price", BigDecimal.valueOf(quote.price()));
                row.put("currency", quote.currency());
            });
            results.add(row);
            if (results.size() >= 8) {
                break;
            }
        }
        ctx.json(results);
    }
}
