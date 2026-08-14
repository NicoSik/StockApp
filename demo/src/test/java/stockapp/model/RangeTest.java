package stockapp.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangeTest {

    @Test
    void parsesEveryLabelTheUiCanSend() {
        assertEquals(Range.DAY, Range.parse("1D"));
        assertEquals(Range.WEEK, Range.parse("1W"));
        assertEquals(Range.MONTH, Range.parse("1M"));
        assertEquals(Range.QUARTER, Range.parse("3M"));
        assertEquals(Range.YEAR, Range.parse("1Y"));
        assertEquals(Range.FIVE_YEAR, Range.parse("5Y"));
    }

    @Test
    void parseIsCaseInsensitiveAndTolerantOfWhitespace() {
        assertEquals(Range.YEAR, Range.parse("1y"));
        assertEquals(Range.WEEK, Range.parse("  1w  "));
    }

    @Test
    void unknownRangeFallsBackToDayRatherThanThrowing() {
        // A query string is user input; a bad value must not 500 the endpoint.
        assertEquals(Range.DAY, Range.parse("banana"));
        assertEquals(Range.DAY, Range.parse(""));
        assertEquals(Range.DAY, Range.parse(null));
    }

    @Test
    void onlyTheDayRangeIsRestrictedToASingleSession() {
        assertTrue(Range.DAY.singleSession());
        for (Range range : Range.values()) {
            if (range != Range.DAY) {
                assertFalse(range.singleSession(), range + " should span multiple sessions");
            }
        }
    }

    @Test
    void dailyAndWeeklyBarsArePersistable() {
        // Only these get written to stock_price; intraday is cached in memory.
        assertTrue(Range.QUARTER.isDaily());
        assertTrue(Range.YEAR.isDaily());
        assertTrue(Range.FIVE_YEAR.isDaily());

        assertFalse(Range.DAY.isDaily());
        assertFalse(Range.WEEK.isDaily());
        assertFalse(Range.MONTH.isDaily());
    }

    @Test
    void lookbackWindowsAreWiderThanTheirLabels() {
        // Markets close for weekends and holidays, so "1W" must reach back more
        // than seven days or a Monday morning chart comes back nearly empty.
        LocalDate today = LocalDate.of(2026, 8, 13);
        assertTrue(today.minus(Range.WEEK.lookback()).isBefore(today.minusDays(7)));
        assertTrue(today.minus(Range.DAY.lookback()).isBefore(today.minusDays(3)));
        assertTrue(today.minus(Range.YEAR.lookback()).isBefore(today.minusYears(1)));
    }

    @Test
    void lookbackSubtractsAsACalendarPeriodNotFixedDays() {
        // Regression guard: converting Period to a Duration double-counted years
        // and made the 5Y chart request roughly ten years of bars.
        LocalDate today = LocalDate.of(2026, 8, 13);
        LocalDate fiveYearsBack = today.minus(Range.FIVE_YEAR.lookback());

        assertEquals(2021, fiveYearsBack.getYear());
        assertTrue(fiveYearsBack.isBefore(today.minusYears(5)));
        assertTrue(fiveYearsBack.isAfter(today.minusYears(6)));
    }

    @Test
    void shorterRangesExpireFromTheCacheSooner() {
        assertTrue(Range.DAY.cacheTtl().compareTo(Range.MONTH.cacheTtl()) < 0);
        assertTrue(Range.MONTH.cacheTtl().compareTo(Range.YEAR.cacheTtl()) < 0);
        assertTrue(Range.YEAR.cacheTtl().compareTo(Range.FIVE_YEAR.cacheTtl()) < 0);
    }
}
