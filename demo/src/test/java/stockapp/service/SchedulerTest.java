package stockapp.service;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerTest {

    private static final long ONE_DAY = 24 * 60 * 60;

    @Test
    void nextRunIsAlwaysInTheFutureAndWithinOneDay() {
        // Whatever the wall clock says, a daily job must be scheduled for a
        // positive delay no further out than 24 hours.
        for (int hour = 0; hour < 24; hour++) {
            long seconds = Scheduler.secondsUntilNext(LocalTime.of(hour, 0));
            assertTrue(seconds > 0, "delay must be positive for " + hour + ":00");
            assertTrue(seconds <= ONE_DAY, "delay must not exceed a day for " + hour + ":00");
        }
    }

    @Test
    void twoJobsAtDifferentTimesGetDifferentDelays() {
        long morning = Scheduler.secondsUntilNext(LocalTime.of(5, 30));
        long afternoon = Scheduler.secondsUntilNext(LocalTime.of(16, 20));
        assertTrue(morning != afternoon);
    }

    @Test
    void aTimeThatHasJustPassedRollsToTomorrowRatherThanFiringImmediately() {
        // Never zero: a zero delay would make the reschedule loop spin.
        LocalTime target = LocalTime.now(java.time.ZoneId.of("America/New_York")).minusMinutes(1);
        long seconds = Scheduler.secondsUntilNext(target);
        assertTrue(seconds > ONE_DAY - 3600, "a just-missed time should wait nearly a full day, got " + seconds);
    }

    // ------------------------------------------------------ holdings day

    @Test
    void theHoldingsDaySyncsEtoroBeforeRefreshingTheHistory() {
        List<String> ran = new ArrayList<>();
        Scheduler.runHoldingsDay(true, () -> ran.add("etoro"), () -> ran.add("history"));
        assertEquals(List.of("etoro", "history"), ran);
    }

    @Test
    void withoutEtoroKeysOnlyTheHistoryIsRefreshed() {
        List<String> ran = new ArrayList<>();
        Scheduler.runHoldingsDay(false, () -> ran.add("etoro"), () -> ran.add("history"));
        assertEquals(List.of("history"), ran);
    }

    @Test
    void aFailedEtoroSyncDoesNotStopTheHistoryRefresh() {
        // Bad keys, a rate limit or a stall must not cost the other accounts
        // their day of history.
        List<String> ran = new ArrayList<>();
        Scheduler.runHoldingsDay(true, () -> {
            throw new IllegalStateException("eToro is not responding");
        }, () -> ran.add("history"));
        assertEquals(List.of("history"), ran);
    }
}
