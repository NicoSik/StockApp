package stockapp.importer;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DNB's asset-class layout ({@code DNBBeholdning.xlsx}), built synthetically as
 * a real xlsx by {@link Workbooks}. Invented holdings only.
 */
class DnbBeholdningParserTest {

    private final DnbBeholdningParser parser = new DnbBeholdningParser();

    // ------------------------------------------------------------ fixtures

    private static final String[] HEADER = {
            "Account", "ISIN", "Security name", "Holdings", "Price date", "Price",
            "Market value", "Asset class", "Sub group", "Operator name", "Dividend"};

    /** 46174 is the Excel serial for 2026-06-01. */
    private static String[] row(String isin, String name, String quantity,
                                String date, String price, String value) {
        return new String[] {"123456789012", isin, name, quantity, date, price, value,
                "Shares", null, "DNB Bank ASA", "0"};
    }

    /** Shares on one sheet, a bond on another, and two empty asset classes. */
    private static byte[] beholdning() {
        Map<String, String[][]> sheets = new LinkedHashMap<>();
        sheets.put("share", new String[][] {HEADER,
                row("NO0010000001", "MOWI ASA", "100", "46174", "150.0", "15000.0"),
                row("NO0010000002", "ORKLA ASA", "200", "46175", "114.5", "22900.0")});
        sheets.put("bond", new String[][] {HEADER,
                row("NO0010000003", "NORDIC BOND 2030", "10", "46174", "1000.0", "10000.0")});
        sheets.put("interestFund", new String[][] {HEADER});
        sheets.put("equityFund", new String[][] {HEADER});
        return Workbooks.workbook(sheets);
    }

    // --------------------------------------------------------------- tests

    @Test
    void recognisesTheAssetClassLayout() {
        assertTrue(parser.supports("DNBBeholdning.xlsx", beholdning()));
        assertFalse(parser.supports("x.xlsx", "not a zip".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theTwoDnbLayoutsDoNotClaimEachOthersFiles() {
        // Both arrive as an .xlsx from the same bank, so the only thing keeping
        // them apart is that each insists on its own columns.
        Map<String, String[][]> aksjer = new LinkedHashMap<>();
        aksjer.put("Aksjer", new String[][] {
                {"Ticker", "Antall", "Verdi", "Avkastning"},
                {"TEL", "165", "22159.5", "99"}});
        byte[] tickerLayout = Workbooks.workbook(aksjer);

        assertFalse(parser.supports("rapport.xlsx", tickerLayout));
        assertFalse(new DnbParser().supports("DNBBeholdning.xlsx", beholdning()));
    }

    @Test
    void readsEveryAssetClassSheetNotJustTheFirst() {
        // A bond sitting on its own sheet is still money, and there is no
        // stated total here to catch it going missing.
        ParsedExport export = parser.parse("DNBBeholdning.xlsx", beholdning());
        assertEquals(3, export.holdings().size());
        assertEquals(0, export.computedTotalNok().compareTo(new BigDecimal("47900.0")));
        assertTrue(export.holdings().stream()
                .anyMatch(holding -> holding.name().equals("NORDIC BOND 2030")));
    }

    @Test
    void identifiesRowsByNameBecauseTheLayoutHasNoTicker() {
        ParsedHolding mowi = parser.parse("b.xlsx", beholdning()).holdings().get(0);
        assertEquals("MOWI ASA", mowi.name());
        assertNull(mowi.ticker(), "the file has an ISIN, not a ticker");
        assertEquals("NOK", mowi.currency());
        assertEquals(0, mowi.quantity().compareTo(new BigDecimal("100")));
        assertEquals(0, mowi.valueNok().compareTo(new BigDecimal("15000.0")));
    }

    @Test
    void carriesTheStatedPriceOnceTheRowProvesItIsANokPrice() {
        ParsedHolding mowi = parser.parse("b.xlsx", beholdning()).holdings().get(0);
        assertEquals(0, mowi.lastPrice().compareTo(new BigDecimal("150.0")));
    }

    @Test
    void dropsAPriceThatDoesNotReproduceTheMarketValue() {
        // The file names no currency. A price that cannot be multiplied back up
        // to the stated value is not a NOK price of this holding, and handing it
        // to the resolver would have it verify against the wrong number.
        Map<String, String[][]> sheets = new LinkedHashMap<>();
        sheets.put("share", new String[][] {HEADER,
                row("US0378331005", "APPLE INC", "10", "46174", "200.0", "22000.0")});

        ParsedHolding apple = parser.parse("b.xlsx", Workbooks.workbook(sheets)).holdings().get(0);
        assertNull(apple.lastPrice());
        assertEquals(0, apple.valueNok().compareTo(new BigDecimal("22000.0")),
                "the holding is still worth what the file says");
    }

    @Test
    void datesTheSnapshotFromTheFileRatherThanTheClock() {
        ParsedExport export = parser.parse("b.xlsx", beholdning());
        assertEquals(LocalDate.of(2026, 6, 2), export.asOf(), "the newest price date in the file");
    }

    @Test
    void readsAWrittenPriceDateToo() {
        assertEquals(LocalDate.of(2026, 6, 1), DnbBeholdningParser.date("2026-06-01"));
        assertEquals(LocalDate.of(2026, 6, 1), DnbBeholdningParser.date("01.06.2026"));
        assertNull(DnbBeholdningParser.date("whenever"));
        assertNull(DnbBeholdningParser.date("12"), "a serial from 1900 is not a price date");
    }

    @Test
    void statesNoTotalAndNoCostBasisBecauseTheFileStatesNeither() {
        // Echoing the sum of the rows back as a "reported" total would only be
        // the parser agreeing with itself, and there is no cost price anywhere
        // in this layout to build a gain from.
        ParsedExport export = parser.parse("b.xlsx", beholdning());
        assertNull(export.reportedTotalNok());
        assertNull(export.reportedCostBasisNok());
        assertNull(export.holdings().get(0).avgCost());
        assertNotNull(export.computedTotalNok());
    }

    @Test
    void skipsRowsWithoutTheNumbersAHoldingNeeds() {
        Map<String, String[][]> sheets = new LinkedHashMap<>();
        sheets.put("share", new String[][] {HEADER,
                row("NO0010000001", "MOWI ASA", "100", "46174", "150.0", "15000.0"),
                row("NO0000000000", "PENDING SETTLEMENT", null, "46174", null, null)});

        ParsedExport export = parser.parse("b.xlsx", Workbooks.workbook(sheets));
        assertEquals(1, export.holdings().size());
    }

    @Test
    void columnOrderIsIrrelevantBecauseHeadersAreMatchedByName() {
        Map<String, String[][]> sheets = new LinkedHashMap<>();
        sheets.put("share", new String[][] {
                {"Market value", "Security name", "ISIN", "Price", "Holdings"},
                {"15000.0", "MOWI ASA", "NO0010000001", "150.0", "100"}});

        ParsedHolding mowi = parser.parse("b.xlsx", Workbooks.workbook(sheets)).holdings().get(0);
        assertEquals("MOWI ASA", mowi.name());
        assertEquals(0, mowi.lastPrice().compareTo(new BigDecimal("150.0")));
        assertEquals(0, mowi.valueNok().compareTo(new BigDecimal("15000.0")));
    }

    @Test
    void aWorkbookWithoutTheColumnsIsRejectedWithAnActionableMessage() {
        Map<String, String[][]> other = new LinkedHashMap<>();
        other.put("Sheet1", new String[][] {{"a", "b"}, {"1", "2"}});
        ImportException error = assertThrows(ImportException.class,
                () -> parser.parse("x.xlsx", Workbooks.workbook(other)));
        assertTrue(error.getMessage().contains("Beholdning"), error.getMessage());
    }

    @Test
    void anExportWithNothingButEmptySheetsIsRejected() {
        Map<String, String[][]> empty = new LinkedHashMap<>();
        empty.put("share", new String[][] {HEADER});
        empty.put("bond", new String[][] {HEADER});
        ImportException error = assertThrows(ImportException.class,
                () -> parser.parse("b.xlsx", Workbooks.workbook(empty)));
        assertTrue(error.getMessage().contains("no holdings"), error.getMessage());
    }
}
