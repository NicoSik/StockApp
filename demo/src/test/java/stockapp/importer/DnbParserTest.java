package stockapp.importer;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DNB's ticker-keyed holdings report, built synthetically as a real xlsx by
 * {@link Workbooks} so the zip and XML handling in {@link XlsxReader} is
 * exercised too. Invented holdings only.
 */
class DnbParserTest {

    private final DnbParser parser = new DnbParser();

    // ------------------------------------------------------------ fixtures

    /** Three holdings summing to 30 000, with a Total sheet that agrees. */
    private static byte[] holdingsReport(String totalValue) {
        Map<String, String[][]> sheets = new LinkedHashMap<>();
        sheets.put("Total", new String[][] {
                {"Urealisert", "Verdi", "Avkastning", "Realisert", "Kostpris"},
                {"5000", totalValue, "120.5", "0", "25000"}});
        sheets.put("Aksjer", new String[][] {
                {"Ticker", "Antall", "Verdi", "Avkastning"},
                {"MOWI", "150", "22159.5", "99"},
                {"ORK", "100", "5000.5", "9.36"},
                {"TOM", "50", "2840", "11.64"}});
        return Workbooks.workbook(sheets);
    }

    /** As above, but the Kostpris cell does not satisfy value - cost = gain. */
    private static byte[] reportWithCostBasis(String costBasis) {
        Map<String, String[][]> sheets = new LinkedHashMap<>();
        sheets.put("Total", new String[][] {
                {"Urealisert", "Verdi", "Avkastning", "Realisert", "Kostpris"},
                {"5000", "30000", "120.5", "0", costBasis}});
        sheets.put("Aksjer", new String[][] {
                {"Ticker", "Antall", "Verdi", "Avkastning"},
                {"MOWI", "150", "22159.5", "99"},
                {"ORK", "100", "5000.5", "9.36"},
                {"TOM", "50", "2840", "11.64"}});
        return Workbooks.workbook(sheets);
    }

    // --------------------------------------------------------------- tests

    @Test
    void recognisesAHoldingsReport() {
        assertTrue(parser.supports("rapport.xlsx", holdingsReport("30000")));
        assertFalse(parser.supports("x.xlsx", "not a zip".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsTickerQuantityAndValue() {
        ParsedExport export = parser.parse("rapport.xlsx", holdingsReport("30000"));
        assertEquals(3, export.holdings().size());

        ParsedHolding mowi = export.holdings().get(0);
        assertEquals("MOWI", mowi.ticker());
        assertEquals("NOK", mowi.currency());
        assertEquals(0, mowi.quantity().compareTo(new BigDecimal("150")));
        assertEquals(0, mowi.valueNok().compareTo(new BigDecimal("22159.5")));
    }

    @Test
    void derivesLastPriceFromValueOverQuantity() {
        // DNB has no price column, but value / quantity is exactly the price -
        // and it is what lets the resolver verify the symbol is the right one.
        ParsedHolding mowi = parser.parse("r.xlsx", holdingsReport("30000")).holdings().get(0);
        assertEquals(0, mowi.lastPrice().compareTo(new BigDecimal("147.730000")));
    }

    @Test
    void leavesPerHoldingCostBasisNullBecauseDnbDoesNotSupplyIt() {
        // Better a visible blank than an invented number.
        assertNull(parser.parse("r.xlsx", holdingsReport("30000")).holdings().get(0).avgCost());
    }

    @Test
    void capturesTheTotalTheFileStatesAboutItself() {
        ParsedExport export = parser.parse("r.xlsx", holdingsReport("30000"));
        assertEquals(0, export.reportedTotalNok().compareTo(new BigDecimal("30000")));
        assertEquals(0, export.computedTotalNok().compareTo(new BigDecimal("30000")));
    }

    @Test
    void capturesThePortfolioCostBasisEvenThoughNoRowHasOne() {
        // The only performance figure DNB gives. Discarding it left an account
        // with a real 5 000 gain showing a dash.
        ParsedExport export = parser.parse("r.xlsx", holdingsReport("30000"));
        assertEquals(0, export.reportedCostBasisNok().compareTo(new BigDecimal("25000")));
    }

    @Test
    void ignoresACostBasisThatDoesNotSatisfyTheSheetsOwnArithmetic() {
        // The Total sheet's headers do not reliably describe their cells - the
        // real report files the total return percentage under "Endring i dag".
        // So Kostpris is trusted only when value - cost equals Urealisert.
        // Reporting no gain beats reporting an invented one.
        ParsedExport export = parser.parse("r.xlsx", reportWithCostBasis("11111"));
        assertNull(export.reportedCostBasisNok());
        assertEquals(3, export.holdings().size(), "the import itself still succeeds");
    }

    @Test
    void refusesAFileWhoseRowsDoNotMatchItsOwnTotal() {
        // The failure this guards against is a silent partial read, which would
        // understate a net worth without anything looking wrong.
        ImportException error = assertThrows(ImportException.class,
                () -> parser.parse("r.xlsx", holdingsReport("99999")));
        assertTrue(error.getMessage().contains("did not reconcile"), error.getMessage());
    }

    @Test
    void rejectsTheOrderHistoryExportWithAnActionableMessage() {
        Map<String, String[][]> orders = new LinkedHashMap<>();
        orders.put("Mine ordre", new String[][] {
                {"Navn", "Ordreretning", "Antall", "Status", "Ticker"},
                {"Mowi ASA", "Selg", "10", "Utført", "MOWI"}});
        ImportException error = assertThrows(ImportException.class,
                () -> parser.parse("Mine_ordre.xlsx", Workbooks.workbook(orders)));
        assertTrue(error.getMessage().contains("Beholdning"), error.getMessage());
    }

    @Test
    void aWorkbookWithoutAnAksjerSheetIsRejected() {
        Map<String, String[][]> other = new LinkedHashMap<>();
        other.put("Sheet1", new String[][] {{"a", "b"}, {"1", "2"}});
        assertThrows(ImportException.class, () -> parser.parse("x.xlsx", Workbooks.workbook(other)));
    }

    @Test
    void columnOrderIsIrrelevantBecauseHeadersAreMatchedByName() {
        Map<String, String[][]> sheets = new LinkedHashMap<>();
        sheets.put("Aksjer", new String[][] {
                {"Avkastning", "Verdi", "Ticker", "Antall"},
                {"99", "22159.5", "MOWI", "150"}});
        ParsedHolding mowi = parser.parse("r.xlsx", Workbooks.workbook(sheets)).holdings().get(0);
        assertEquals("MOWI", mowi.ticker());
        assertEquals(0, mowi.valueNok().compareTo(new BigDecimal("22159.5")));
    }

    @Test
    void spreadsheetColumnReferencesDecodeBeyondZ() {
        assertEquals(0, XlsxReader.columnOf("A1"));
        assertEquals(1, XlsxReader.columnOf("B3"));
        assertEquals(25, XlsxReader.columnOf("Z9"));
        assertEquals(26, XlsxReader.columnOf("AA1"));
        assertEquals(27, XlsxReader.columnOf("AB12"));
    }
}
