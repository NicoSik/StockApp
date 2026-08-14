package stockapp.importer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DNB's holdings report, built synthetically as a real xlsx so the zip and XML
 * handling in {@link XlsxReader} is exercised too. Invented holdings only.
 */
class DnbParserTest {

    private final DnbParser parser = new DnbParser();

    // ------------------------------------------------------------ fixtures

    /** Builds a minimal but genuine xlsx from sheet name -> grid of cells. */
    private static byte[] workbook(Map<String, String[][]> sheets) {
        List<String> shared = new java.util.ArrayList<>();
        StringBuilder workbookXml = new StringBuilder(
                "<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        StringBuilder relsXml = new StringBuilder("<Relationships>");
        Map<String, String> sheetXml = new LinkedHashMap<>();

        int index = 0;
        for (Map.Entry<String, String[][]> entry : sheets.entrySet()) {
            index++;
            String rid = "rId" + index;
            workbookXml.append("<sheet name=\"").append(entry.getKey())
                    .append("\" sheetId=\"").append(index).append("\" r:id=\"").append(rid).append("\"/>");
            relsXml.append("<Relationship Id=\"").append(rid)
                    .append("\" Target=\"worksheets/sheet").append(index).append(".xml\"/>");

            StringBuilder rows = new StringBuilder("<worksheet><sheetData>");
            for (int r = 0; r < entry.getValue().length; r++) {
                rows.append("<row r=\"").append(r + 1).append("\">");
                String[] row = entry.getValue()[r];
                for (int c = 0; c < row.length; c++) {
                    String value = row[c];
                    if (value == null) {
                        continue;
                    }
                    String ref = (char) ('A' + c) + String.valueOf(r + 1);
                    if (isNumeric(value)) {
                        rows.append("<c r=\"").append(ref).append("\"><v>").append(value).append("</v></c>");
                    } else {
                        int si = shared.indexOf(value);
                        if (si < 0) {
                            shared.add(value);
                            si = shared.size() - 1;
                        }
                        rows.append("<c r=\"").append(ref).append("\" t=\"s\"><v>").append(si).append("</v></c>");
                    }
                }
                rows.append("</row>");
            }
            rows.append("</sheetData></worksheet>");
            sheetXml.put("xl/worksheets/sheet" + index + ".xml", rows.toString());
        }
        workbookXml.append("</sheets></workbook>");
        relsXml.append("</Relationships>");

        StringBuilder sharedXml = new StringBuilder("<sst>");
        for (String s : shared) {
            sharedXml.append("<si><t>").append(s.replace("&", "&amp;")).append("</t></si>");
        }
        sharedXml.append("</sst>");

        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out)) {
            write(zip, "xl/workbook.xml", workbookXml.toString());
            write(zip, "xl/_rels/workbook.xml.rels", relsXml.toString());
            write(zip, "xl/sharedStrings.xml", sharedXml.toString());
            for (Map.Entry<String, String> sheet : sheetXml.entrySet()) {
                write(zip, sheet.getKey(), sheet.getValue());
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isNumeric(String value) {
        return value.matches("-?\\d+(\\.\\d+)?");
    }

    private static void write(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + content).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Three holdings summing to 30 000, with a Total sheet that agrees. */
    private static byte[] holdingsReport(String totalValue) {
        Map<String, String[][]> sheets = new LinkedHashMap<>();
        sheets.put("Total", new String[][] {
                {"Urealisert", "Verdi", "Avkastning", "Realisert", "Kostpris"},
                {"5000", totalValue, "120.5", "0", "25000"}});
        sheets.put("Aksjer", new String[][] {
                {"Ticker", "Antall", "Verdi", "Avkastning"},
                {"TEL", "165", "22159.5", "99"},
                {"NHY", "117", "5000.5", "9.36"},
                {"AKRBP", "62", "2840", "11.64"}});
        return workbook(sheets);
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

        ParsedHolding tel = export.holdings().get(0);
        assertEquals("TEL", tel.ticker());
        assertEquals("NOK", tel.currency());
        assertEquals(0, tel.quantity().compareTo(new BigDecimal("165")));
        assertEquals(0, tel.valueNok().compareTo(new BigDecimal("22159.5")));
    }

    @Test
    void derivesLastPriceFromValueOverQuantity() {
        // DNB has no price column, but value / quantity is exactly the price -
        // and it is what lets the resolver verify the symbol is the right one.
        ParsedHolding tel = parser.parse("r.xlsx", holdingsReport("30000")).holdings().get(0);
        assertEquals(0, tel.lastPrice().compareTo(new BigDecimal("134.300000")));
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
                {"Yara International ASA", "Selg", "10", "Utført", "YAR"}});
        ImportException error = assertThrows(ImportException.class,
                () -> parser.parse("Mine_ordre.xlsx", workbook(orders)));
        assertTrue(error.getMessage().contains("Beholdning"), error.getMessage());
    }

    @Test
    void aWorkbookWithoutAnAksjerSheetIsRejected() {
        Map<String, String[][]> other = new LinkedHashMap<>();
        other.put("Sheet1", new String[][] {{"a", "b"}, {"1", "2"}});
        assertThrows(ImportException.class, () -> parser.parse("x.xlsx", workbook(other)));
    }

    @Test
    void columnOrderIsIrrelevantBecauseHeadersAreMatchedByName() {
        Map<String, String[][]> sheets = new LinkedHashMap<>();
        sheets.put("Aksjer", new String[][] {
                {"Avkastning", "Verdi", "Ticker", "Antall"},
                {"99", "22159.5", "TEL", "165"}});
        ParsedHolding tel = parser.parse("r.xlsx", workbook(sheets)).holdings().get(0);
        assertEquals("TEL", tel.ticker());
        assertEquals(0, tel.valueNok().compareTo(new BigDecimal("22159.5")));
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
