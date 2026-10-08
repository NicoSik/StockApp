package stockapp.service;

import stockapp.importer.BrokerParser;
import stockapp.importer.DnbBeholdningParser;
import stockapp.importer.DnbParser;
import stockapp.importer.ImportException;
import stockapp.importer.NordnetParser;
import stockapp.importer.ParsedExport;
import stockapp.importer.ParsedHolding;
import stockapp.market.InstrumentResolver;
import stockapp.repo.AccountRepo;
import stockapp.repo.InstrumentRepo;
import stockapp.yahoo.YahooClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns an uploaded broker export into a stored snapshot.
 *
 * <p>Always in two steps. A parse produces a <b>preview</b> in which every row
 * carries how it was resolved and how confident that is; nothing is written
 * until the preview is committed. That matters because identity is usually
 * recovered from a name or a ticker - only DNB's {@code DNBBeholdning.xlsx}
 * and hand-entered funds carry an ISIN - so a mapping is usually an inference,
 * and an inference should be looked at once before it starts feeding a
 * net-worth figure.
 *
 * <p>Once a row is committed its broker-specific label is remembered as an
 * alias, so the same holding never has to be reviewed again.
 */
public final class ImportService {

    private static final List<BrokerParser> PARSERS =
            List.of(new NordnetParser(), new DnbParser(), new DnbBeholdningParser());
    /** A preview is a scratch object; half an hour is more than a person needs. */
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(30);

    /** Norwegian funds are bought and reported in kroner; there is no choice. */
    private static final String FUND_CURRENCY = "NOK";

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
                             String isin,
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
                        "Unrecognised file. Expected a Nordnet holdings export (.csv) or one of DNB's "
                                + "two holdings reports (.xlsx)."));

        return resolveInto(parser.parse(filename, content), null);
    }

    /**
     * One fund, typed in by hand.
     *
     * <p>Not every fund can be imported. DNB's asset-class export has
     * equityFund and interestFund sheets, but its Norwegian report is shares
     * only and Nordnet's export has no fund rows at all - so a fund held there
     * is invisible, and with it roughly a third of the portfolio, until it is
     * entered here.
     *
     * @param isin  optional, and the only unambiguous identifier: a fund search
     *              returns six share classes that differ by one letter
     * @param units what a NAV is derived from, so the price check can tell those
     *              classes apart. Without it a fund can only be carried at the
     *              value given, never priced.
     */
    public record ManualFund(String name, String isin, BigDecimal units,
                             BigDecimal valueNok, BigDecimal costBasisNok) {
    }

    /**
     * Resolves hand-entered funds without writing anything.
     *
     * <p>The result is an ordinary {@link Preview}, so these go through exactly
     * the same confirm, re-map, skip and commit path as an imported file. A
     * fund is not a special kind of holding once it has been identified.
     */
    public Preview previewFunds(String accountName, String broker, List<ManualFund> funds) {
        if (funds == null || funds.isEmpty()) {
            throw new ImportException("No funds were entered.");
        }
        List<ParsedHolding> holdings = new ArrayList<>(funds.size());
        for (ManualFund fund : funds) {
            if (fund.name() == null || fund.name().isBlank()) {
                throw new ImportException("Every fund needs a name.");
            }
            if (fund.valueNok() == null || fund.valueNok().signum() <= 0) {
                throw new ImportException("\"%s\" needs a value.".formatted(fund.name().trim()));
            }
            BigDecimal units = fund.units();
            // Value over units is the NAV, which is what identifies the share
            // class - the same derivation that resolves DNB's shares, which
            // also arrive without a price column.
            BigDecimal nav = units == null || units.signum() == 0
                    ? null
                    : fund.valueNok().divide(units, 6, RoundingMode.HALF_UP);
            BigDecimal avgCost = fund.costBasisNok() == null || units == null || units.signum() == 0
                    ? null
                    : fund.costBasisNok().divide(units, 6, RoundingMode.HALF_UP);

            holdings.add(new ParsedHolding(
                    fund.name().trim(),
                    null,
                    fund.isin() == null || fund.isin().isBlank() ? null : fund.isin().trim(),
                    FUND_CURRENCY, units, avgCost, nav, fund.valueNok(), fund.valueNok()));
        }

        ParsedExport export = new ParsedExport(broker, LocalDate.now(),
                "entered by hand", holdings, null);
        return resolveInto(export, accountName);
    }

    /**
     * Turns a parsed export into a preview, resolving every row.
     *
     * @param accountName the account to file it under, or null to use the
     *                    broker's default - funds live in their own account so
     *                    that re-importing a share export cannot replace them
     */
    private Preview resolveInto(ParsedExport export, String accountName) {
        List<PreviewRow> rows = new ArrayList<>(export.holdings().size());
        int confirmed = 0;
        int review = 0;
        int unresolved = 0;

        for (int i = 0; i < export.holdings().size(); i++) {
            ParsedHolding holding = export.holdings().get(i);

            // A remembered mapping short-circuits the lookup - but only if it
            // was actually verified. An unverified alias is a guess someone
            // declined to endorse, and treating it as settled would make a
            // rejected match permanent and invisible on every later import.
            Optional<InstrumentRepo.Instrument> known = findKnown(export.broker(), holding);
            if (known.isPresent()) {
                rows.add(new PreviewRow(i, holding.name(), holding.ticker(), holding.isin(), holding.currency(),
                        holding.quantity(), holding.avgCost(), holding.lastPrice(), holding.valueNok(),
                        "CONFIRMED", known.get().symbol(), known.get().name(), null,
                        "Previously mapped", true));
                confirmed++;
                continue;
            }

            InstrumentResolver.Resolution resolution = resolver.resolve(
                    holding.ticker(), holding.isin(), holding.name(), holding.currency(), holding.lastPrice());

            switch (resolution.status()) {
                case CONFIRMED -> confirmed++;
                case NEEDS_REVIEW -> review++;
                case UNRESOLVED -> unresolved++;
            }
            rows.add(new PreviewRow(i, holding.name(), holding.ticker(), holding.isin(), holding.currency(),
                    holding.quantity(), holding.avgCost(), holding.lastPrice(), holding.valueNok(),
                    resolution.status().name(), resolution.symbol(), resolution.name(),
                    resolution.livePrice(), resolution.note(), false));
        }

        Preview preview = new Preview(
                UUID.randomUUID().toString(),
                export.broker(),
                accountName != null && !accountName.isBlank()
                        ? accountName.trim()
                        : defaultAccountName(export.broker()),
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
        // Kept with each holding so a match fixed later can be remembered.
        Map<Integer, String> labels = new HashMap<>();
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
            labels.putIfAbsent(instrument.id(), aliasFor(row));
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
                preview.costBasisNok(), stored, labels);
        pending.invalidate(previewId);

        return new Result(account.id(), account.name(), snapshotId, stored.size(), skipped, preview.totalNok());
    }

    public record Result(int accountId, String accountName, int snapshotId,
                         int imported, int skipped, BigDecimal totalNok) {
    }

    // -------------------------------------------------------------- fix match

    /**
     * What {@link #fixMatch} did.
     *
     * @param holdings   how many holdings were repriced, across every snapshot
     *                   of the account that has this one
     * @param remembered whether the next import will know the match without
     *                   asking; false for holdings imported before labels
     *                   were kept, whose label is not known
     */
    public record Fixed(String symbol, String name, int holdings, boolean remembered) {
    }

    /**
     * Gives an imported holding the symbol the user picked, outside an import:
     * the same as choosing it on the preview screen, after the fact. The
     * holding is priced live from then on, in every snapshot of the account
     * that has it, and the match is remembered for the next import.
     *
     * <p>The holding is moved to the chosen symbol's instrument rather than
     * the instrument it has being edited: an unconfirmed instrument can be
     * shared by holdings in other accounts that are something else.
     *
     * @throws ImportException when the account is synced rather than imported,
     *         the holding is not in its latest snapshot, or the symbol has no
     *         price in the holding's currency
     */
    public Fixed fixMatch(int accountId, int instrumentId, String symbol) {
        AccountRepo.Account account = accounts.findAccount(accountId)
                .orElseThrow(() -> new ImportException("There is no account " + accountId + "."));
        if ("LINKED".equals(account.kind())) {
            throw new ImportException(account.name() + " is synced, and its source values its holdings; "
                    + "there is no match to fix.");
        }
        AccountRepo.Snapshot latest = accounts.latestSnapshot(accountId)
                .orElseThrow(() -> new ImportException(account.name() + " has nothing imported."));
        AccountRepo.StoredHolding holding = accounts.holdings(latest.id()).stream()
                .filter(candidate -> candidate.instrumentId() == instrumentId)
                .findFirst()
                .orElseThrow(() -> new ImportException("That holding is not in " + account.name()
                        + "'s latest import."));
        InstrumentRepo.Instrument current = instruments.findById(instrumentId).orElseThrow();

        YahooClient.Quote quote = resolver.describe(symbol)
                .orElseThrow(() -> new ImportException("No price for " + (symbol == null ? "" : symbol.trim())
                        + ". Check the symbol, with its exchange suffix (EQNR.OL, VOLV-B.ST)."));
        String refusal = currencyRefusal(holding.currency(), quote);
        if (refusal != null) {
            throw new ImportException(refusal);
        }

        String name = quote.name() != null && !quote.name().isBlank() ? quote.name() : holding.name();
        // Picked by hand, so verified - exactly as on the preview screen.
        InstrumentRepo.Instrument chosen = instruments.upsert(
                quote.symbol(), name, holding.currency(), current.kind(), "YAHOO", true);
        int moved = chosen.id() == current.id()
                ? 1  // the guess was right; confirming it is the whole fix
                : remap(accountId, current.id(), chosen.id(), quote.symbol());

        String label = rememberUnder(accounts.label(latest.id(), instrumentId).orElse(null), current);
        if (label != null) {
            instruments.linkAlias(account.broker(), label, chosen.id());
        }
        return new Fixed(quote.symbol(), name, moved, label != null);
    }

    private int remap(int accountId, int from, int to, String symbol) {
        try {
            return accounts.remapInstrument(accountId, from, to);
        } catch (IllegalArgumentException e) {
            throw new ImportException("This account already holds " + symbol
                    + " as a separate line, so this holding cannot become it too.");
        }
    }

    /**
     * Why a quote cannot price a holding, or null when it can. Valuation
     * converts the live price from the holding's currency, so a listing in
     * another currency would be valued wrong by the exchange rate.
     */
    static String currencyRefusal(String holdingCurrency, YahooClient.Quote quote) {
        if (holdingCurrency == null || holdingCurrency.isBlank()
                || quote.currency() == null || quote.currency().isBlank()
                || holdingCurrency.equalsIgnoreCase(quote.currency())) {
            return null;
        }
        return quote.symbol() + " trades in " + quote.currency() + ", but this holding is in "
                + holdingCurrency + ". Pick the listing that trades in " + holdingCurrency + ".";
    }

    /**
     * The label to remember a fixed match under, so the next import finds it:
     * the one the holding came in under. Older holdings did not keep one; for
     * those, an instrument the import never matched still carries the row's own
     * name, but one it matched wrongly carries the wrong match's name, which
     * must not be remembered. Null when there is nothing safe to remember.
     */
    static String rememberUnder(String label, InstrumentRepo.Instrument current) {
        if (label != null && !label.isBlank()) {
            return label.trim();
        }
        if (current.symbol() == null || current.symbol().isBlank()) {
            return current.name() == null || current.name().isBlank() ? null : current.name().trim();
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The labels a row can be remembered under, strongest first: its ISIN, its
     * ticker, then its name.
     *
     * <p>A new match is stored under the first. Lookups try them all, so a
     * match remembered under a name before a file carried an ISIN is still
     * found - re-importing with the ISIN does not throw away a choice a person
     * already confirmed.
     */
    static List<String> aliasKeys(String isin, String ticker, String name) {
        List<String> keys = new ArrayList<>(3);
        for (String key : new String[] {isin, ticker, name}) {
            if (key != null && !key.isBlank() && !keys.contains(key.trim())) {
                keys.add(key.trim());
            }
        }
        return keys;
    }

    /** A verified instrument this row was mapped to before, under any of its labels. */
    private Optional<InstrumentRepo.Instrument> findKnown(String broker, ParsedHolding holding) {
        for (String key : aliasKeys(holding.isin(), holding.ticker(), holding.name())) {
            Optional<InstrumentRepo.Instrument> known = instruments.findByAlias(broker, key);
            if (known.isPresent() && known.get().verified()) {
                return known;
            }
        }
        return Optional.empty();
    }

    /** The label a confirmed row is remembered under. */
    private static String aliasFor(PreviewRow row) {
        return aliasKeys(row.isin(), row.ticker(), row.name()).get(0);
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
