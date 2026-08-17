package stockapp.importer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the parsers against whatever real exports happen to be sitting in
 * {@code imports/}.
 *
 * <p>Those files contain actual holdings and are gitignored, so these tests
 * skip themselves anywhere the files are absent - a clean checkout, another
 * machine, CI. That is deliberate: the synthetic fixtures in
 * {@link NordnetParserTest} and {@link DnbParserTest} are the ones that must
 * pass everywhere, and these are the ones that prove the parsers cope with what
 * the banks actually emit.
 *
 * <p>Drop a fresh export in and re-run to check a format has not changed.
 */
class RealExportTest {

    /** The tests run from demo/, so imports/ is one level up. */
    private static final Path IMPORTS = Path.of("..", "imports");

    private static byte[] read(String filename) {
        Path path = IMPORTS.resolve(filename);
        assumeTrue(Files.isRegularFile(path), "no " + filename + " in imports/ - skipping");
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void parsesTheRealNordnetExport() {
        byte[] content = read("nordnet-sample.csv");
        NordnetParser parser = new NordnetParser();

        assertTrue(parser.supports("nordnet-sample.csv", content),
                "the real export should be recognised by content");

        ParsedExport export = parser.parse("nordnet-sample.csv", content);
        List<ParsedHolding> holdings = export.holdings();

        assertFalse(holdings.isEmpty(), "expected holdings");
        System.out.printf("[real] Nordnet: %d holdings, %s NOK%n",
                holdings.size(), export.computedTotalNok().setScale(2, RoundingMode.HALF_UP));

        for (ParsedHolding holding : holdings) {
            assertTrue(holding.name() != null && !holding.name().isBlank(), "every row needs a name");
            assertTrue(holding.quantity().signum() > 0, holding.name() + " should have a positive quantity");
            assertTrue(holding.valueNok().signum() >= 0, holding.name() + " should have a value");
            assertTrue(holding.currency() != null, holding.name() + " should carry a currency");
        }

        // A decimal comma read as a thousands separator would inflate values by
        // orders of magnitude; a sane per-share price is the cheapest guard.
        for (ParsedHolding holding : holdings) {
            if (holding.lastPrice() != null && holding.lastPrice().signum() > 0) {
                assertTrue(holding.lastPrice().compareTo(new BigDecimal("1000000")) < 0,
                        holding.name() + " has an implausible price - decimal comma mis-parsed?");
            }
        }
    }

    @Test
    void parsesTheRealDnbReport() {
        byte[] content = read("dnb-rapport.xlsx");
        DnbParser parser = new DnbParser();

        assertTrue(parser.supports("dnb-rapport.xlsx", content),
                "the real report should be recognised by content");

        // parse() reconciles rows against the Total sheet internally and throws
        // if they disagree, so reaching this line is itself the assertion.
        ParsedExport export = parser.parse("dnb-rapport.xlsx", content);

        assertFalse(export.holdings().isEmpty(), "expected holdings");
        System.out.printf("[real] DNB: %d holdings, %s NOK (file states %s)%n",
                export.holdings().size(),
                export.computedTotalNok().setScale(2, RoundingMode.HALF_UP),
                export.reportedTotalNok());

        // No row carries a cost price, but the Total sheet does for the account
        // as a whole - the one figure that lets this account report a gain.
        assertNotNull(export.reportedCostBasisNok(), "Total sheet should state Kostpris");
        assertTrue(export.reportedCostBasisNok().signum() > 0);
        System.out.printf("[real] DNB cost basis %s NOK, unrealised %s NOK%n",
                export.reportedCostBasisNok(),
                export.computedTotalNok().subtract(export.reportedCostBasisNok())
                        .setScale(2, RoundingMode.HALF_UP));

        for (ParsedHolding holding : export.holdings()) {
            assertTrue(holding.ticker() != null && !holding.ticker().isBlank(),
                    "DNB supplies a ticker for every holding");
            assertTrue(holding.quantity().signum() > 0);
            assertTrue(holding.lastPrice() != null && holding.lastPrice().signum() > 0,
                    holding.ticker() + " should have a derived price");
        }
    }

    @Test
    void theTwoAccountsCombineIntoOneTotal() {
        byte[] nordnet = read("nordnet-sample.csv");
        byte[] dnb = read("dnb-rapport.xlsx");

        BigDecimal a = new NordnetParser().parse("n.csv", nordnet).computedTotalNok();
        BigDecimal b = new DnbParser().parse("d.xlsx", dnb).computedTotalNok();
        BigDecimal combined = a.add(b).setScale(2, RoundingMode.HALF_UP);

        System.out.printf("[real] combined: %s NOK%n", combined);
        assertTrue(combined.signum() > 0);
    }
}
