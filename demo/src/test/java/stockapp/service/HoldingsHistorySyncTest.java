package stockapp.service;

import org.junit.jupiter.api.Test;
import stockapp.repo.ClosingPriceRepo;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which date ranges still have to be fetched, given what is stored. */
class HoldingsHistorySyncTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-07");

    private static HoldingsHistorySync.Window window(String from, String to) {
        return new HoldingsHistorySync.Window(LocalDate.parse(from), LocalDate.parse(to));
    }

    private static ClosingPriceRepo.StoredRange stored(String first, String last) {
        return new ClosingPriceRepo.StoredRange(LocalDate.parse(first), LocalDate.parse(last));
    }

    @Test
    void nothingStoredFetchesEverythingFromTheNeedToToday() {
        assertEquals(List.of(window("2026-08-10", "2026-10-07")),
                HoldingsHistorySync.missing(LocalDate.parse("2026-08-10"), null, TODAY));
    }

    @Test
    void storedDataIsOnlyToppedUpAtTheEnd() {
        // The last stored days are fetched again: a close taken while a
        // session was open was a live price, and is replaced by the real one.
        assertEquals(List.of(window("2026-10-01", "2026-10-07")),
                HoldingsHistorySync.missing(LocalDate.parse("2026-08-10"),
                        stored("2026-08-10", "2026-10-06"), TODAY));
    }

    @Test
    void anOlderImportFetchesTheGapBeforeTheStoredData() {
        assertEquals(List.of(window("2026-07-01", "2026-08-09"), window("2026-10-01", "2026-10-07")),
                HoldingsHistorySync.missing(LocalDate.parse("2026-07-01"),
                        stored("2026-08-10", "2026-10-06"), TODAY));
    }

    @Test
    void aFewDaysShortOfTheNeedIsNotAGap() {
        // The need often falls on a weekend or holiday, so the first close is a
        // few days later. Re-asking every run would never get a closer answer.
        assertEquals(List.of(window("2026-10-01", "2026-10-07")),
                HoldingsHistorySync.missing(LocalDate.parse("2026-08-08"),
                        stored("2026-08-10", "2026-10-06"), TODAY));
    }

    @Test
    void theTopUpNeverStartsBeforeTheNeed() {
        // Stored data from long ago, but nothing older than the need is wanted.
        assertEquals(List.of(window("2026-10-05", "2026-10-07")),
                HoldingsHistorySync.missing(LocalDate.parse("2026-10-05"),
                        stored("2026-01-01", "2026-10-06"), TODAY));
    }

    @Test
    void aNeedAfterTodayFetchesNothing() {
        assertEquals(List.of(), HoldingsHistorySync.missing(LocalDate.parse("2026-10-08"), null, TODAY));
    }
}
