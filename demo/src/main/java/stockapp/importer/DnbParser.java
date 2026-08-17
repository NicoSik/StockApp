package stockapp.importer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads DNB's holdings report ({@code rapport.xlsx}).
 *
 * <p>Two sheets: <b>Aksjer</b> with one row per holding
 * ({@code Ticker | Antall | Verdi | Avkastning}), and <b>Total</b> with a
 * single row of portfolio figures.
 *
 * <p>This is the easier of the two supported formats, because it carries a
 * <b>ticker</b>. All of them are Oslo Børs, so appending {@code .OL} gives a
 * Yahoo symbol directly and none of the name-guessing the Nordnet importer
 * needs applies.
 *
 * <p>Two things it does not provide, which are left null rather than invented:
 * per-holding cost basis (DNB reports {@code Kostpris} only for the portfolio
 * as a whole) and a last price. The price is derived as value / quantity, which
 * is exact and gives the resolver something to verify a symbol against.
 *
 * <p>Do not confuse this with DNB's "Mine ordre" export, which is an order
 * history for the last twelve months. Positions cannot be reconstructed from
 * it - anything bought earlier simply is not there - so it is rejected.
 */
public final class DnbParser implements BrokerParser {

    public static final String BROKER = "DNB";

    private static final String SHEET_HOLDINGS = "aksjer";
    private static final String SHEET_TOTAL = "total";

    private static final String COL_TICKER = "ticker";
    private static final String COL_QUANTITY = "antall";
    private static final String COL_VALUE = "verdi";
    /** Portfolio-level only; the per-holding sheet has no equivalent. */
    private static final String COL_COST_BASIS = "kostpris";
    /** Used to prove the cost basis is what its header claims - see below. */
    private static final String COL_UNREALISED = "urealisert";

    /** DNB reports NOK; there is no currency column because there is no need. */
    private static final String CURRENCY = "NOK";

    /**
     * The sheet total and the sum of rows must agree to within a krone. They
     * reconcile exactly in practice, so any real gap means rows were missed.
     */
    private static final BigDecimal RECONCILE_TOLERANCE = new BigDecimal("1.00");

    @Override
    public String broker() {
        return BROKER;
    }

    @Override
    public boolean supports(String filename, byte[] content) {
        if (content == null || content.length < 4
                || content[0] != 'P' || content[1] != 'K') {
            return false;
        }
        try {
            return findSheet(XlsxReader.read(content), SHEET_HOLDINGS) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public ParsedExport parse(String filename, byte[] content) {
        Map<String, List<List<String>>> sheets = XlsxReader.read(content);

        List<List<String>> holdingsSheet = findSheet(sheets, SHEET_HOLDINGS);
        if (holdingsSheet == null) {
            if (findSheet(sheets, "mine ordre") != null || hasColumn(sheets, "ordreretning")) {
                throw new ImportException(
                        "This is DNB's order history, not a holdings report. It only covers the last 12 months, "
                                + "so it cannot show what you currently own. Export \"Beholdning\" instead.");
            }
            throw new ImportException("No \"Aksjer\" sheet found - is this a DNB holdings report?");
        }
        if (holdingsSheet.size() < 2) {
            throw new ImportException("The Aksjer sheet has no holdings.");
        }

        Map<String, Integer> columns = headerIndex(holdingsSheet.get(0));
        Integer tickerIdx = columns.get(COL_TICKER);
        Integer quantityIdx = columns.get(COL_QUANTITY);
        Integer valueIdx = columns.get(COL_VALUE);
        if (tickerIdx == null || quantityIdx == null || valueIdx == null) {
            throw new ImportException("The Aksjer sheet is missing a Ticker, Antall or Verdi column.");
        }

        List<ParsedHolding> holdings = new ArrayList<>();
        for (int r = 1; r < holdingsSheet.size(); r++) {
            List<String> row = holdingsSheet.get(r);
            String ticker = XlsxReader.at(row, tickerIdx);
            BigDecimal quantity = number(XlsxReader.at(row, quantityIdx));
            BigDecimal value = number(XlsxReader.at(row, valueIdx));
            if (ticker == null || quantity == null || value == null) {
                continue;
            }
            // No price column, but value / quantity is exactly the price, and
            // that is what lets the resolver prove the symbol is the right one.
            BigDecimal lastPrice = quantity.signum() == 0
                    ? null
                    : value.divide(quantity, 6, RoundingMode.HALF_UP);

            holdings.add(new ParsedHolding(
                    ticker, ticker, CURRENCY, quantity, null, lastPrice, value, value));
        }

        if (holdings.isEmpty()) {
            throw new ImportException("No holdings could be read from the Aksjer sheet.");
        }

        // The Aksjer sheet has no cost price per row, so per-holding gain is
        // genuinely unavailable. Its Avkastning column is not that gain either:
        // the rows sum to the Total sheet's change today, not to its unrealised
        // gain. The Total sheet does state Kostpris for the account, which is
        // the only performance figure DNB provides - worth carrying rather than
        // discarding.
        BigDecimal reportedTotal = readTotalField(sheets, COL_VALUE);
        BigDecimal reportedCost = costBasisIfItAddsUp(sheets, reportedTotal);
        ParsedExport export = new ParsedExport(
                BROKER, LocalDate.now(), filename, holdings, reportedTotal, reportedCost);
        reconcile(export);
        return export;
    }

    /**
     * Refuses an import whose rows do not add up to the total the file states
     * about itself.
     *
     * <p>Cheap, and it turns a silent partial read - the failure mode that
     * would quietly understate a net worth - into a visible error.
     */
    private static void reconcile(ParsedExport export) {
        BigDecimal reported = export.reportedTotalNok();
        if (reported == null) {
            return;
        }
        BigDecimal computed = export.computedTotalNok();
        if (computed.subtract(reported).abs().compareTo(RECONCILE_TOLERANCE) > 0) {
            throw new ImportException(
                    "The file did not reconcile: the holdings add up to %s NOK but the Total sheet says %s NOK. "
                            .formatted(computed.setScale(2, RoundingMode.HALF_UP), reported)
                            + "Refusing to import a partial read.");
        }
    }

    /**
     * The stated cost basis, but only once the file has proved it is one.
     *
     * <p>The Total sheet's headers do not reliably describe the cells under
     * them. In a real report the column headed {@code Endring i dag} holds not
     * a change in kroner but the total return as a percentage; the one headed
     * {@code Avkastning} holds the change today; and the name
     * {@code Avkastning} appears twice in the same header row, so a
     * name-to-index map silently keeps whichever comes last. Reading
     * {@code Kostpris} by header alone is therefore a guess, and a wrong one
     * would put a fabricated gain on screen - the failure this whole import
     * path exists to avoid.
     *
     * <p>The sheet also states {@code Urealisert}, so it carries its own proof:
     * value minus cost must equal unrealised gain, and on a real report it does
     * to the øre. If the identity does not hold, the cell is not what its
     * header says and no cost basis is reported - the account then shows no
     * gain, which is the honest outcome.
     */
    private static BigDecimal costBasisIfItAddsUp(Map<String, List<List<String>>> sheets,
                                                  BigDecimal reportedTotal) {
        BigDecimal cost = readTotalField(sheets, COL_COST_BASIS);
        BigDecimal unrealised = readTotalField(sheets, COL_UNREALISED);
        if (cost == null || unrealised == null || reportedTotal == null) {
            return null;
        }
        BigDecimal implied = reportedTotal.subtract(cost);
        return implied.subtract(unrealised).abs().compareTo(RECONCILE_TOLERANCE) > 0 ? null : cost;
    }

    /** Reads one named column from the single row of the Total sheet. */
    private static BigDecimal readTotalField(Map<String, List<List<String>>> sheets, String column) {
        List<List<String>> total = findSheet(sheets, SHEET_TOTAL);
        if (total == null || total.size() < 2) {
            return null;
        }
        Integer index = headerIndex(total.get(0)).get(column);
        return index == null ? null : number(XlsxReader.at(total.get(1), index));
    }

    private static List<List<String>> findSheet(Map<String, List<List<String>>> sheets, String name) {
        for (Map.Entry<String, List<List<String>>> entry : sheets.entrySet()) {
            if (entry.getKey().trim().toLowerCase(Locale.ROOT).equals(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean hasColumn(Map<String, List<List<String>>> sheets, String column) {
        for (List<List<String>> rows : sheets.values()) {
            for (List<String> row : rows) {
                for (String cell : row) {
                    if (cell != null && cell.trim().equalsIgnoreCase(column)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Map<String, Integer> headerIndex(List<String> header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String cell = header.get(i);
            if (cell != null && !cell.isBlank()) {
                columns.put(cell.trim().toLowerCase(Locale.ROOT), i);
            }
        }
        return columns;
    }

    /** Excel stores numbers with a dot; be tolerant of a comma regardless. */
    private static BigDecimal number(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim().replace(" ", "").replace(" ", "").replace(",", ".");
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
