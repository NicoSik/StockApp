package stockapp.alpaca;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlpacaClientTest {

    @Test
    void parsesASecondPrecisionTimestamp() {
        assertEquals(Instant.parse("2026-08-12T04:00:00Z").toEpochMilli(),
                AlpacaClient.toEpochMillis("2026-08-12T04:00:00Z"));
    }

    @Test
    void parsesNanosecondPrecisionTimestamps() {
        // Alpaca reports trade times to the nanosecond; truncating to millis is
        // fine for charting but the parser must not reject the extra digits.
        long millis = AlpacaClient.toEpochMillis("2026-08-12T19:59:59.557163993Z");
        assertEquals(Instant.parse("2026-08-12T19:59:59.557Z").toEpochMilli(), millis);
    }

    @Test
    void unparseableInputYieldsZeroRatherThanThrowing() {
        // A single malformed bar must not take down a whole chart request.
        assertEquals(0L, AlpacaClient.toEpochMillis("not a timestamp"));
        assertEquals(0L, AlpacaClient.toEpochMillis(""));
        assertEquals(0L, AlpacaClient.toEpochMillis(null));
    }
}
