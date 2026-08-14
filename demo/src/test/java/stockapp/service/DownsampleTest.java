package stockapp.service;

import org.junit.jupiter.api.Test;
import stockapp.model.Candle;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the sparkline reducer in {@link MarketData}. */
class DownsampleTest {

    private static List<Candle> bars(int count) {
        List<Candle> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            bars.add(new Candle(i * 60_000L, i, i, i, i, 1));
        }
        return bars;
    }

    @Test
    void shortSeriesArePassedThroughUntouched() {
        double[] points = MarketData.downsample(bars(10), 48);
        assertEquals(10, points.length);
        assertEquals(0.0, points[0]);
        assertEquals(9.0, points[9]);
    }

    @Test
    void aSeriesExactlyAtTheTargetIsNotResampled() {
        double[] points = MarketData.downsample(bars(48), 48);
        assertEquals(48, points.length);
        assertEquals(47.0, points[47]);
    }

    @Test
    void longSeriesAreReducedToTheTargetLength() {
        double[] points = MarketData.downsample(bars(390), 48);
        assertEquals(48, points.length);
    }

    @Test
    void theLastPointIsAlwaysTheMostRecentClose() {
        // The sparkline must end on the current price; dropping the final bar
        // would leave the row disagreeing with the price beside it.
        for (int size : new int[] {49, 100, 389, 390, 1000}) {
            double[] points = MarketData.downsample(bars(size), 48);
            assertEquals(size - 1.0, points[points.length - 1],
                    "last point should be the final bar for a series of " + size);
        }
    }

    @Test
    void theFirstPointIsAlwaysTheOldestClose() {
        double[] points = MarketData.downsample(bars(500), 48);
        assertEquals(0.0, points[0]);
    }

    @Test
    void sampledPointsStayInChronologicalOrder() {
        double[] points = MarketData.downsample(bars(500), 48);
        for (int i = 1; i < points.length; i++) {
            assertTrue(points[i] > points[i - 1], "points must increase monotonically at index " + i);
        }
    }

    @Test
    void aSingleBarDoesNotDivideByZero() {
        double[] points = MarketData.downsample(bars(1), 48);
        assertEquals(1, points.length);
        assertEquals(0.0, points[0]);
    }
}
