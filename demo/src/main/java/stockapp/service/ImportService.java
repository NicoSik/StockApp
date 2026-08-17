package stockapp.service;

import stockapp.importer.BrokerParser;
import stockapp.importer.DnbParser;
import stockapp.importer.ImportException;
import stockapp.importer.NordnetParser;
import stockapp.importer.ParsedExport;
import stockapp.importer.ParsedHolding;
import stockapp.market.InstrumentResolver;
import stockapp.repo.AccountRepo;
import stockapp.repo.InstrumentRepo;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns an uploaded broker export into a stored snapshot.
 *
 * <p>Always in two steps. A parse produces a <b>preview</b> in which every row
 * carries how it was resolved and how confident that is; nothing is written
 * until the preview is committed. That matters because neither broker supplies
 * an ISIN, so a mapping is an inference, and an inference should be looked at
 * once before it starts feeding a net-worth figure.
 *
 * <p>Once a row is committed its broker-specific label is remembered as an
 * alias, so the same holding never has to be reviewed again.
 */
public final class ImportService {

    private static final List<BrokerParser> PARSERS = List.of(new NordnetParser(), new DnbParser());
    /** A preview is a scratch object; half an hour is more than a person needs. */
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(30);

    private final AccountRepo accounts;
    private final InstrumentRepo instruments;
    private final InstrumentResolver resolver;
    private final Cache<String, Preview> pending = new Cache<>();

    public ImportService(AccountRepo accounts, InstrumentRepo instruments, InstrumentResolver resolver) {
        this.accounts = accounts;
        this.instruments = instruments;
        this.resolver = resolver;
    }

    /**
     * One row awaiting confirmation.
     *
     * @param status     CONFIRMED, NEEDS_REVIEW or UNRESOLVED
     * @param knownAlias true when this broker's label was already mapped, in
     *                   which case no lookup happened at all
     */
    public record PreviewRow(int index,
                             String name,
                             String ticker,
                             String currency,
                             BigDecimal quantity,
                             BigDecimal avgCost,
                             BigDecimal lastPrice,
                             BigDecimal valueNok,
                             String status,
                             String symbol,
                             String resolvedName,
                             Double livePrice,
                             String note,
                             boolean knownAlias) {
    }

    public record Preview(String id,
                          String broker,
                          String accountName,
                          String sourceFile,
                          LocalDate asOf,
                          List<PreviewRow> rows,
                          BigDecimal totalNok,
                          BigDecimal costBasisNok,
                          int confirmed,
                          int needsReview,
                          int unresolved) {
    }

    /** Parses and resolves an upload without writing anything. */
    public Preview preview(String filename, byte[] content) {
        BrokerParser parser = PARSERS.stream()
                .filter(p -> p.supports(filename, content))
                .findFirst()
                .orElseThrow(() -> new ImportException(
                        "Unrecognised file. Expected a Nordnet holdings export (.csv) or a DNB "
                                + "\"Beholdning\" report (.xlsx)."));

        ParsedExport export = parser.parse(filename, content);
        List<PreviewRow> rows = new ArrayList<>(export.holdings().size());
        int confirmed = 0;
        int review = 0;
        int unresolved = 0;

        for (int i = 0; i < export.holdings().size(); i++) {
            ParsedHolding holding = export.holdings().get(i);
            String alias = aliasFor(holding);

            // A remembered mapping short-circuits the lookup - but only if it
            // was actually verified. An unverified alias is a guess someone
            // declined to endorse, and treating it as settled would make a
            // rejected match permanent and invisible on every later import.
            Optional<InstrumentRepo.Instrument> known = instruments.findByAlias(export.broker(), alias);
            if (known.isPresent() && known.get().verified()) {
                rows.add(new PreviewRow(i, holding.name(), holding.ticker(), holding.currency(),
                        holding.quantity(), holding.avgCost(), holding.lastPrice(), holding.valueNok(),
                        "CONFIRMED", known.get().symbol(), known.get().name(), null,
                        "Previously mapped", true));
                confirmed++;
                continue;
            }

            InstrumentResolver.Resolution resolution = resolver.resolve(
                    holding.ticker(), holding.name(), holding.currency(), holding.lastPrice());

            switch (resolution.status()) {
                case CONFIRMED -> confirmed++;
                case NEEDS_REVIEW -> review++;
                case UNRESOLVED -> unresolved++;
            }
            rows.add(new PreviewRow(i, holding.name(), holding.ticker(), holding.currency(),
                    holding.quantity(), holding.avgCost(), holding.lastPrice(), holding.valueNok(),
                    resolution.status().name(), resolution.symbol(), resolution.name(),
                    resolution.livePrice(), resolution.note(), false));
        }

        Preview preview = new Preview(
                UUID.randomUUID().toString(),
                export.broker(),
                defaultAccountName(export.broker()),
                export.sourceFile(),
                export.asOf(),
                rows,
                export.computedTotalNok(),
                export.reportedCostBasisNok(),
                confirmed, review, unresolved);

        pending.put(preview.id(), preview, PREVIEW_TTL);
        return preview;
    }

    /**
     * Writes a previewed import.
     *
     * @param overrides row index to symbol, for anything the user re-mapped or
     *                  confirmed by hand in the reconcile screen
     * @param skip      row indices to leave out entirely
     */
    public Result commit(String previewId, Map<Integer, String> overrides, List<Integer> skip) {
        Preview preview = pending.peekStale(previewId);
        if (preview == null) {
            throw new ImportException("That import has expired. Upload the file again.");
        }

        AccountRepo.Account account = accounts.ensureAccount(
                preview.accountName(), preview.broker(), "IMPORTED");

        List<AccountRepo.StoredHolding> stored = new ArrayList<>();
        int skipped = 0;
        for (PreviewRow row : preview.rows()) {
            if (skip != null && skip.contains(row.index())) {
                skipped++;
                continue;
            }
            String symbol = overrides == null ? null : overrides.get(row.index());
            if (symbol == null || symbol.isBlank()) {
                symbol = row.symbol();
            }

            // A row with no symbol is still worth keeping: it holds the value
            // the broker reported, which is exactly how funds are carried.
            boolean priceable = symbol != null && !symbol.isBlank();
            // Confirmed automatically, or chosen by hand: both count as
            // verified, and verification is never revoked later.
            boolean overridden = overrides != null && overrides.containsKey(row.index());
            boolean verified = "CONFIRMED".equals(row.status()) || overridden;

            // On an override, resolvedName belongs to the match that was
            // rejected, so it must not be carried over onto the corrected
            // symbol. Ask what the chosen symbol actually is instead.
            String name = row.resolvedName() != null ? row.resolvedName() : row.name();
            if (overridden) {
                name = resolver.describe(symbol)
                        .map(quote -> quote.name())
                        .filter(found -> found != null && !found.isBlank())
                        .orElse(row.name());
            }

            InstrumentRepo.Instrument instrument = instruments.upsert(
                    priceable ? symbol : null,
                    name,
                    row.currency(),
                    guessKind(row),
                    priceable ? "YAHOO" : "NONE",
                    verified);

            // Only remember the mapping if it was actually settled. Caching an
            // unverified guess would silently skip the price check on every
            // future import of the same holding - which is exactly how a wrong
            // match becomes permanent.
            if (verified) {
                instruments.linkAlias(preview.broker(), aliasFor(row), instrument.id());
            }
            stored.add(new AccountRepo.StoredHolding(
                    instrument.id(), instrument.symbol(), instrument.name(), row.currency(),
                    instrument.kind(), instrument.priceSource(), instrument.verified(),
                    row.quantity(), row.avgCost(), row.valueNok()));
        }

        if (stored.isEmpty()) {
            throw new ImportException("Every row was skipped; there is nothing to import.");
        }

        int snapshotId = accounts.writeSnapshot(
                account.id(), preview.asOf(), preview.sourceFile(), preview.totalNok(),
                preview.costBasisNok(), stored);
        pending.invalidate(previewId);

        return new Result(account.id(), account.name(), snapshotId, stored.size(), skipped, preview.totalNok());
    }

    public record Result(int accountId, String accountName, int snapshotId,
                         int imported, int skipped, BigDecimal totalNok) {
    }

    // ---------------------------------------------------------------- helpers

    /** The broker's own label: its ticker where it has one, else the name. */
    private static String aliasFor(ParsedHolding holding) {
        return holding.ticker() != null && !holding.ticker().isBlank()
                ? holding.ticker().trim()
                : holding.name().trim();
    }

    private static String aliasFor(PreviewRow row) {
        return row.ticker() != null && !row.ticker().isBlank() ? row.ticker().trim() : row.name().trim();
    }

    private static String defaultAccountName(String broker) {
        return switch (broker) {
            case NordnetParser.BROKER -> "Nordnet";
            case DnbParser.BROKER -> "DNB";
            default -> broker;
        };
    }

    /** ETFs are worth distinguishing; anything unpriceable is treated as a fund. */
    private static String guessKind(PreviewRow row) {
        String name = (row.resolvedName() != null ? row.resolvedName() : row.name()).toUpperCase();
        if (name.contains("ETF")) {
            return "ETF";
        }
        if (row.symbol() == null || row.symbol().isBlank()) {
            return "FUND";
        }
        return "STOCK";
    }
}
