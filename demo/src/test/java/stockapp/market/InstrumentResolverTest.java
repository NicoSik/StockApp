package stockapp.market;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure parts of instrument resolution. The network-dependent half is
 * exercised against the real broker exports instead.
 */
class InstrumentResolverTest {

    @Test
    void aPlainNameIsSearchedAsWritten() {
        assertEquals(List.of("Equinor"), InstrumentResolver.queriesFor("Equinor"));
    }

    @Test
    void nordnetShareClassSuffixesGetASecondAttemptWithoutThem() {
        // "Oscar Health A" finds nothing on Yahoo; "Oscar Health" finds OSCR.
        // The original is still tried first, in case a name genuinely ends in a
        // capital letter.
        assertEquals(List.of("Oscar Health A", "Oscar Health"),
                InstrumentResolver.queriesFor("Oscar Health A"));
        assertEquals(List.of("Snap A", "Snap"), InstrumentResolver.queriesFor("Snap A"));
        assertEquals(List.of("Clover Health Investments A", "Clover Health Investments"),
                InstrumentResolver.queriesFor("Clover Health Investments A"));
    }

    @Test
    void multiLetterEndingsAreNotMistakenForAShareClass() {
        // "NEL" must not be truncated to "NE".
        assertEquals(List.of("NEL"), InstrumentResolver.queriesFor("NEL"));
        assertEquals(List.of("K33"), InstrumentResolver.queriesFor("K33"));
    }

    @Test
    void blankNamesProduceNoQueries() {
        assertTrue(InstrumentResolver.queriesFor(null).isEmpty());
        assertTrue(InstrumentResolver.queriesFor("  ").isEmpty());
    }

    @Test
    void currencyDeterminesTheExchangeSuffix() {
        // This is what stops a NOK holding resolving to a US listing of the
        // same name - the reason "DNB" must not become Dun & Bradstreet.
        assertEquals(".OL", YahooClient.suffixForCurrency("NOK"));
        assertEquals(".ST", YahooClient.suffixForCurrency("SEK"));
        assertEquals(".CO", YahooClient.suffixForCurrency("DKK"));
        assertEquals("", YahooClient.suffixForCurrency("USD"));
        assertEquals("", YahooClient.suffixForCurrency(null));
    }

    @Test
    void suffixLookupIsCaseInsensitive() {
        assertEquals(".OL", YahooClient.suffixForCurrency("nok"));
    }
}
