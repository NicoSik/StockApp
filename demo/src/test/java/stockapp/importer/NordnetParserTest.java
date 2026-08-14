package stockapp.importer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nordnet's export is UTF-16LE, tab-delimited and full of decimal commas,
 * despite being named .csv. The fixtures here are synthetic - shaped exactly
 * like a real export but with invented holdings, because real ones do not
 * belong in a public repository.
 */
class NordnetParserTest {

    private static final String HEADER =
            "Navn\tValuta\tAntall\tGAV\tI dag %\tSiste kurs\tBelåningsverdi\tVerdi\tVerdi NOK\tAvkast. %\tAvkast. NOK";

    /** Writes text as UTF-16LE with a BOM, exactly as Nordnet does. */
    private static byte[] utf16le(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xFE);
        byte[] body = text.getBytes(StandardCharsets.UTF_16LE);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static byte[] sample() {
        return utf16le(String.join("\n",
                HEADER,
                "Kongsberg Gruppen\tNOK\t170\t189,0672\t2,1884498\t336,2\t48580,9\t57154\t57154\t77,82\t25012,5816",
                "Palantir Technologies\tUSD\t25\t35,8848\t-0,0055863\t179\t29582,58\t4475\t42260,8367172\t343,99\t32742,36",
                "K33\tSEK\t35260\t0,1447\t2,0242915\t0,0252\t0\t888,552\t882,9274658\t-82,27\t-4096,99"));
    }

    private final NordnetParser parser = new NordnetParser();

    @Test
    void recognisesTheFormatFromItsContentNotItsName() {
        assertTrue(parser.supports("anything.txt", sample()));
        assertFalse(parser.supports("x.csv", "Name,Value\nA,1".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void decodesUtf16leAndSplitsOnTabs() {
        ParsedExport export = parser.parse("aksjelister.csv", sample());
        assertEquals(3, export.holdings().size());
        assertEquals("Kongsberg Gruppen", export.holdings().get(0).name());
        assertEquals("Palantir Technologies", export.holdings().get(1).name());
    }

    @Test
    void readsDecimalCommasAsDecimalPoints() {
        ParsedHolding kog = parser.parse("f.csv", sample()).holdings().get(0);
        assertEquals(0, kog.quantity().compareTo(new BigDecimal("170")));
        assertEquals(0, kog.avgCost().compareTo(new BigDecimal("189.0672")));
        assertEquals(0, kog.lastPrice().compareTo(new BigDecimal("336.2")));
        assertEquals(0, kog.valueNok().compareTo(new BigDecimal("57154")));
    }

    @Test
    void keepsNativeCurrencyAndNokValueApart() {
        // Nordnet does the conversion for us; both numbers matter, and
        // conflating them would silently mis-value every foreign holding.
        ParsedHolding pltr = parser.parse("f.csv", sample()).holdings().get(1);
        assertEquals("USD", pltr.currency());
        assertEquals(0, pltr.valueNative().compareTo(new BigDecimal("4475")));
        assertEquals(0, pltr.valueNok().compareTo(new BigDecimal("42260.8367172")));
    }

    @Test
    void handlesAThirdCurrency() {
        ParsedHolding k33 = parser.parse("f.csv", sample()).holdings().get(2);
        assertEquals("SEK", k33.currency());
        assertEquals(0, k33.quantity().compareTo(new BigDecimal("35260")));
    }

    @Test
    void nordnetSuppliesNoTicker() {
        // The reason the resolver has to work from names at all.
        assertNull(parser.parse("f.csv", sample()).holdings().get(0).ticker());
    }

    @Test
    void totalsAddUp() {
        BigDecimal total = parser.parse("f.csv", sample()).computedTotalNok();
        assertEquals(0, total.compareTo(new BigDecimal("57154").add(new BigDecimal("42260.8367172"))
                .add(new BigDecimal("882.9274658"))));
    }

    @Test
    void alsoReadsPlainUtf8ShouldNordnetEverChange() {
        byte[] utf8 = String.join("\n", HEADER,
                "Equinor\tNOK\t81\t293,2701\t1,88\t384\t24883,2\t31104\t31104\t30,94\t7349,12")
                .getBytes(StandardCharsets.UTF_8);
        ParsedExport export = parser.parse("f.csv", utf8);
        assertEquals("Equinor", export.holdings().get(0).name());
    }

    @Test
    void spacedThousandsSeparatorsAreStripped() {
        // Norwegian formatting uses a space, sometimes a non-breaking one.
        assertEquals(0, NordnetParser.number("1 234,56").compareTo(new BigDecimal("1234.56")));
        assertEquals(0, NordnetParser.number("1 234,56").compareTo(new BigDecimal("1234.56")));
        assertNull(NordnetParser.number(""));
        assertNull(NordnetParser.number("  "));
    }

    @Test
    void blankLinesAndRowsWithoutANameAreSkipped() {
        byte[] gappy = utf16le(String.join("\n", HEADER,
                "Equinor\tNOK\t81\t293,2701\t1,88\t384\t24883,2\t31104\t31104\t30,94\t7349,12",
                "",
                "\tNOK\t10\t1\t0\t1\t0\t10\t10\t0\t0"));
        assertEquals(1, parser.parse("f.csv", gappy).holdings().size());
    }

    @Test
    void aFileWithoutTheExpectedColumnsIsRejectedClearly() {
        byte[] wrong = utf16le("Alpha\tBeta\nx\ty");
        ImportException error = assertThrows(ImportException.class, () -> parser.parse("f.csv", wrong));
        assertNotNull(error.getMessage());
        assertTrue(error.getMessage().toLowerCase().contains("nordnet"));
    }

    @Test
    void aHeaderOnlyFileIsRejectedRatherThanImportedAsEmpty() {
        assertThrows(ImportException.class, () -> parser.parse("f.csv", utf16le(HEADER)));
    }
}
