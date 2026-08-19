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
                "Mowi\tNOK\t120\t250,5\t1,1\t300,25\t24000\t36030\t36030\t19,86\t5970",
                "Example Corp\tUSD\t40\t12,3456\t-0,55\t20,5\t5000\t820\t7749,2367172\t66,05\t3082,36",
                "Zeta Mining\tSEK\t42000\t0,2\t2,02\t0,0315\t0\t1323\t1284,9274658\t-84,25\t-6900,15"));
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
        assertEquals("Mowi", export.holdings().get(0).name());
        assertEquals("Example Corp", export.holdings().get(1).name());
    }

    @Test
    void readsDecimalCommasAsDecimalPoints() {
        ParsedHolding mowi = parser.parse("f.csv", sample()).holdings().get(0);
        assertEquals(0, mowi.quantity().compareTo(new BigDecimal("120")));
        assertEquals(0, mowi.avgCost().compareTo(new BigDecimal("250.5")));
        assertEquals(0, mowi.lastPrice().compareTo(new BigDecimal("300.25")));
        assertEquals(0, mowi.valueNok().compareTo(new BigDecimal("36030")));
    }

    @Test
    void keepsNativeCurrencyAndNokValueApart() {
        // Nordnet does the conversion for us; both numbers matter, and
        // conflating them would silently mis-value every foreign holding.
        ParsedHolding usd = parser.parse("f.csv", sample()).holdings().get(1);
        assertEquals("USD", usd.currency());
        assertEquals(0, usd.valueNative().compareTo(new BigDecimal("820")));
        assertEquals(0, usd.valueNok().compareTo(new BigDecimal("7749.2367172")));
    }

    @Test
    void handlesAThirdCurrency() {
        ParsedHolding sek = parser.parse("f.csv", sample()).holdings().get(2);
        assertEquals("SEK", sek.currency());
        assertEquals(0, sek.quantity().compareTo(new BigDecimal("42000")));
    }

    @Test
    void nordnetSuppliesNoTicker() {
        // The reason the resolver has to work from names at all.
        assertNull(parser.parse("f.csv", sample()).holdings().get(0).ticker());
    }

    @Test
    void totalsAddUp() {
        BigDecimal total = parser.parse("f.csv", sample()).computedTotalNok();
        assertEquals(0, total.compareTo(new BigDecimal("36030").add(new BigDecimal("7749.2367172"))
                .add(new BigDecimal("1284.9274658"))));
    }

    @Test
    void alsoReadsPlainUtf8ShouldNordnetEverChange() {
        byte[] utf8 = String.join("\n", HEADER,
                "Tomra Systems\tNOK\t60\t180,4\t1,88\t210\t10080\t12600\t12600\t16,41\t1776")
                .getBytes(StandardCharsets.UTF_8);
        ParsedExport export = parser.parse("f.csv", utf8);
        assertEquals("Tomra Systems", export.holdings().get(0).name());
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
                "Tomra Systems\tNOK\t60\t180,4\t1,88\t210\t10080\t12600\t12600\t16,41\t1776",
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
