package stockapp.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.UploadedFile;
import stockapp.importer.ImportException;
import stockapp.market.YahooClient;
import stockapp.repo.AccountRepo;
import stockapp.repo.InstrumentRepo;
import stockapp.service.ImportService;
import stockapp.service.ValuationService;

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
    private final YahooClient yahoo;

    public AggregatorApi(AccountRepo accounts,
                         InstrumentRepo instruments,
                         ImportService imports,
                         ValuationService valuation,
                         YahooClient yahoo) {
        this.accounts = accounts;
        this.instruments = instruments;
        this.imports = imports;
        this.valuation = valuation;
        this.yahoo = yahoo;
    }

    public void register(RoutesConfig routes) {
        routes.exception(ImportException.class, (e, ctx) ->
                ctx.status(HttpStatus.UNPROCESSABLE_CONTENT).json(Map.of("error", e.getMessage())));

        routes.get("/api/holdings", ctx -> ctx.json(valuation.valueEverything()));
        routes.get("/api/holdings/history", ctx -> ctx.json(Map.of("points", valuation.history())));
        routes.get("/api/holdings/accounts", ctx -> ctx.json(accounts.listAccounts()));
        routes.get("/api/holdings/instruments", ctx -> ctx.json(instruments.listAll()));

        routes.post("/api/holdings/import/preview", this::previewImport);
        routes.post("/api/holdings/import/commit", this::commitImport);

        // Backs the reconcile screen: lets the user search for the right symbol
        // when the automatic match was refused.
        routes.get("/api/holdings/lookup", this::lookup);
    }

    // ---------------------------------------------------------------- import

    private void previewImport(Context ctx) {
        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) {
            throw new Json.BadRequest("No file was uploaded.");
        }
        if (file.size() > MAX_UPLOAD_BYTES) {
            throw new Json.BadRequest("That file is larger than 8 MB; broker exports are a few kilobytes.");
        }

        byte[] content;
        try (InputStream in = file.content()) {
            content = in.readAllBytes();
        } catch (IOException e) {
            throw new ImportException("The upload could not be read.", e);
        }
        ctx.json(imports.preview(file.filename(), content));
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
                    throw new Json.BadRequest("\"overrides\" keys must be row numbers.");
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
                    throw new Json.BadRequest("\"skip\" must be an array of row numbers.");
                }
            }
        }

        ImportService.Result result = imports.commit(previewId, overrides, skip);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", result);
        response.put("holdings", valuation.valueEverything());
        ctx.status(HttpStatus.CREATED).json(response);
    }

    // ---------------------------------------------------------------- lookup

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
