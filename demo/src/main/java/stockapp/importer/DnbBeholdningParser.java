package stockapp.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads DNB's other holdings export - the one split by asset class.
 *
 * <p>DNB emits two different workbooks and calls both of them a holdings
 * report. {@link DnbParser} reads the Norwegian one, with an {@code Aksjer}
 * sheet keyed by ticker and a {@code Total} sheet. This reads the one that
 * comes out as {@code DNBBeholdning.xlsx}: English headers, one sheet per asset
 * class ({@code share}, {@code bond}, {@code interestFund},
 * {@code equityFund}), and columns
 * {@code Account | ISIN | Security name | Holdings | Price date | Price |
 * Market value | Asset class | Sub group | Operator name | Dividend}.
 *
 * <p>Three consequences follow from that layout, and they are the whole of what
 * this class does differently:
 *
 * <ol>
 *   <li><b>Every sheet is read, not just the first.</b> The asset-class sheets
 *       are usually empty for a share-only account, but a bond or a fund that
 *       lives on its own sheet is still money. Dropping a sheet would
 *       understate a net worth silently, and unlike the {@code Aksjer} layout
 *       there is no stated total here to catch it - so the guard has to be that
 *       nothing is skipped in the first place.</li>
 *   <li><b>There is no ticker.</b> Identity is the ISIN, which is exact, but
 *       nothing downstream takes one yet, so rows are resolved from
 *       {@code Security name} the way Nordnet's are. The names are clean
 *       ("MOWI ASA", "ORKLA ASA") and every row carries a price, so the
 *       resolver still gets to verify what it finds.</li>
 *   <li><b>There is no Total sheet and no cost price anywhere.</b> Both the
 *       reported total and the cost basis are left null rather than filled with
 *       the sum of the rows, which would only be the parser agreeing with
 *       itself.</li>
 * </ol>
 *
 * <p>The file states no currency. A DNB custody statement is valued in NOK, so
 * that is what rows are labelled - but {@code Price} is trusted as a NOK price
 * only when quantity times price reproduces the market value, because that is
 * the one thing that would go wrong first if a holding were ever priced in its
 * own currency. When it does not reproduce it, no price is reported and the
 * resolver hands the row to a human instead of verifying against a number in
 * the wrong currency.
 */
public final class DnbBeholdningParser implements BrokerParser {

    /** The same bank, so an alias learned from either layout serves both. */
    public static final String BROKER = DnbParser.BROKER;

    private static final String COL_ISIN = "isin";
    private static final String COL_NAME = "security name";
    private static final String COL_QUANTITY = "holdings";
    private static final String COL_PRICE = "price";
    private static final String COL_PRICE_DATE = "price date";
    private static final String COL_VALUE = "market value";

    /** Stated nowhere in the file; see the note on the class. */
    private static final String CURRENCY = "NOK";

    /**
     * How far quantity times price may sit from the stated market value before
     * the price is treated as not being a NOK price of this holding. Generous
     * enough for a price rounded to two decimals, far tighter than any currency
     * conversion.
     */
    private static final BigDecimal PRICE_TOLERANCE = new BigDecimal("0.01");
    private static final BigDecimal ONE_KRONE = new BigDecimal("1.00");

    /** Excel's day zero, with the 1900 leap-year bug already accounted for. */
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);
    private static final LocalDate EXCEL_DATE_FLOOR = LocalDate.of(1990, 1, 1);
    private static final LocalDate EXCEL_DATE_CEILING = LocalDate.of(2100, 1, 1);
    private static final DateTimeFormatter NORWEGIAN_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");

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
            for (List<List<String>> rows : XlsxReader.read(content).values()) {
                if (isHoldingsSheet(rows) && XlsxReader.headerIndex(rows.get(0)).containsKey(COL_ISIN)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public ParsedExport parse(String filename, byte[] content) {
        Map<String, List<List<String>>> sheets = XlsxReader.read(content);

        List<ParsedHolding> holdings = new ArrayList<>();
        LocalDate asOf = null;
        boolean recognised = false;

        for (List<List<String>> rows : sheets.values()) {
            if (!isHoldingsSheet(rows)) {
                continue;
            }
            recognised = true;

            Map<String, Integer> columns = XlsxReader.headerIndex(rows.get(0));
            int nameIdx = columns.get(COL_NAME);
            int quantityIdx = columns.get(COL_QUANTITY);
            int valueIdx = columns.get(COL_VALUE);
            Integer priceIdx = columns.get(COL_PRICE);
            Integer dateIdx = columns.get(COL_PRICE_DATE);

            for (int r = 1; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                String name = XlsxReader.at(row, nameIdx);
                BigDecimal quantity = XlsxReader.number(XlsxReader.at(row, quantityIdx));
                BigDecimal value = XlsxReader.number(XlsxReader.at(row, valueIdx));
                if (name == null || quantity == null || value == null) {
                    continue;
                }
                BigDecimal price = priceIdx == null
                        ? null
                        : XlsxReader.number(XlsxReader.at(row, priceIdx));

                holdings.add(new ParsedHolding(name, null, CURRENCY, quantity, null,
                        priceInNok(quantity, price, value), value, value));
                asOf = later(asOf, dateIdx == null ? null : date(XlsxReader.at(row, dateIdx)));
            }
        }

        if (!recognised) {
            throw new ImportException(
                    "No sheet with a Security name, Holdings and Market value column - "
                            + "is this a DNB \"Beholdning\" export?");
        }
        if (holdings.isEmpty()) {
            throw new ImportException("The export lists no holdings.");
        }

        // The file dates itself, which beats dating the snapshot from the clock
        // of whoever happens to import it.
        return new ParsedExport(BROKER, asOf == null ? LocalDate.now() : asOf, filename, holdings, null, null);
    }

    /** A sheet is one of ours when its header row names the three columns we need. */
    private static boolean isHoldingsSheet(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        Map<String, Integer> columns = XlsxReader.headerIndex(rows.get(0));
        return columns.containsKey(COL_NAME)
                && columns.containsKey(COL_QUANTITY)
                && columns.containsKey(COL_VALUE);
    }

    /**
     * The stated price, but only once the row has shown it to be a NOK price of
     * this holding: quantity times price has to come back to the market value.
     *
     * <p>The price exists to be checked against a live quote, so a price in the
     * wrong unit is worse than none at all - it would put a real holding in
     * front of a human as a suspected wrong instrument, or worse, quietly agree
     * with a wrong one.
     */
    private static BigDecimal priceInNok(BigDecimal quantity, BigDecimal price, BigDecimal value) {
        if (price == null || price.signum() <= 0 || quantity.signum() == 0) {
            return null;
        }
        BigDecimal gap = quantity.multiply(price).subtract(value).abs();
        BigDecimal allowed = value.abs().multiply(PRICE_TOLERANCE).max(ONE_KRONE);
        return gap.compareTo(allowed) > 0 ? null : price;
    }

    /** "Price date" arrives as an Excel serial; a written date is read too. */
    static LocalDate date(String raw) {
        if (raw == null) {
            return null;
        }
        BigDecimal serial = XlsxReader.number(raw);
        if (serial != null) {
            LocalDate date = EXCEL_EPOCH.plusDays(serial.longValue());
            // A serial that lands outside living memory is not a date at all.
            return date.isBefore(EXCEL_DATE_FLOOR) || date.isAfter(EXCEL_DATE_CEILING) ? null : date;
        }
        for (DateTimeFormatter format : List.of(DateTimeFormatter.ISO_LOCAL_DATE, NORWEGIAN_DATE)) {
            try {
                return LocalDate.parse(raw.trim(), format);
            } catch (DateTimeParseException ignored) {
                // Try the next shape before giving up.
            }
        }
        return null;
    }

    /** The newest price date in the file is what the snapshot is as of. */
    private static LocalDate later(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        return b == null || b.isBefore(a) ? a : b;
    }
}
