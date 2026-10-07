package stockapp.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The order in which a row's labels are used to remember and find a match. */
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
}
