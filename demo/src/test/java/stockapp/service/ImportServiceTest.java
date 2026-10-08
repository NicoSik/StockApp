package stockapp.service;

import org.junit.jupiter.api.Test;
import stockapp.repo.InstrumentRepo;
import stockapp.yahoo.YahooClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order in which a row's labels are used to remember and find a match,
 * and the checks on a match fixed after the import.
 */
class ImportServiceTest {

    @Test
    void anIsinComesFirstThenTheTickerThenTheName() {
        assertEquals(List.of("NO0010000001", "MOWI", "MOWI ASA"),
                ImportService.aliasKeys("NO0010000001", "MOWI", "MOWI ASA"));
    }

    @Test
    void theNameStillComesAfterAnIsin() {
        // A match remembered under the name, before the file carried an ISIN,
        // must still be found once it does.
        assertEquals(List.of("NO0010000001", "MOWI ASA"),
                ImportService.aliasKeys("NO0010000001", null, "MOWI ASA"));
    }

    @Test
    void blanksAndDuplicatesAreSkipped() {
        // DNB's report gives its ticker as both the ticker and the name.
        assertEquals(List.of("TEL"), ImportService.aliasKeys(" ", "TEL", "TEL"));
        assertEquals(List.of("AEye A"), ImportService.aliasKeys(null, null, " AEye A "));
    }

    // --- fixing a match after the import -------------------------------------

    private static YahooClient.Quote quote(String symbol, String currency) {
        return new YahooClient.Quote(symbol, 100.0, currency, symbol + " Inc", 0L, 99.0);
    }

    @Test
    void aListingInTheHoldingsCurrencyIsAccepted() {
        assertNull(ImportService.currencyRefusal("USD", quote("LIDR", "USD")));
        assertNull(ImportService.currencyRefusal("nok", quote("EQNR.OL", "NOK")), "case does not matter");
    }

    @Test
    void aListingInAnotherCurrencyIsRefused() {
        // Valuation converts the live price from the holding's currency, so
        // Equinor's NYSE listing would value an Oslo holding wrong by the rate.
        String refusal = ImportService.currencyRefusal("NOK", quote("EQNR", "USD"));
        assertTrue(refusal != null && refusal.contains("EQNR trades in USD"), refusal);
    }

    @Test
    void anUnknownCurrencyOnEitherSideIsNotARefusal() {
        assertNull(ImportService.currencyRefusal(null, quote("LIDR", "USD")));
        assertNull(ImportService.currencyRefusal("USD", quote("LIDR", "")));
    }

    private static InstrumentRepo.Instrument instrument(String symbol, String name) {
        return new InstrumentRepo.Instrument(1, null, symbol, name, "USD", "STOCK", "YAHOO", false);
    }

    @Test
    void aFixIsRememberedUnderTheLabelTheRowCameInUnder() {
        assertEquals("AEye A", ImportService.rememberUnder(" AEye A ", instrument("AEYE", "AudioEye, Inc.")));
    }

    @Test
    void anOldUnmatchedHoldingIsRememberedUnderItsOwnName() {
        // The import never matched it, so the instrument still has the row's name.
        assertEquals("AEye A", ImportService.rememberUnder(null, instrument(null, "AEye A")));
    }

    @Test
    void anOldWronglyMatchedHoldingIsNotRemembered() {
        // Its instrument carries the wrong match's name, not the row's, and
        // remembering that would map a real AudioEye row to the fix.
        assertNull(ImportService.rememberUnder(null, instrument("AEYE", "AudioEye, Inc.")));
    }
}
