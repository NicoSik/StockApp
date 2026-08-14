package stockapp.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class QuoteTest {

    private static Quote quote(double price, Double previousClose) {
        return Quote.of("AAPL", price, previousClose, null, null, null, null, 0L, 0L);
    }

    @Test
    void computesChangeAgainstThePreviousClose() {
        Quote q = quote(302.20, 304.885);
        assertEquals(-2.685, q.change(), 1e-9);
        assertEquals(-0.8806599, q.changePercent(), 1e-6);
    }

    @Test
    void computesAPositiveChange() {
        Quote q = quote(110.0, 100.0);
        assertEquals(10.0, q.change(), 1e-9);
        assertEquals(10.0, q.changePercent(), 1e-9);
    }

    @Test
    void changeIsNullWhenThereIsNoPreviousClose() {
        // A newly listed symbol has no prior session. The UI renders a dash for
        // null, which is honest; zero would claim the price was flat.
        Quote q = quote(50.0, null);
        assertNull(q.change());
        assertNull(q.changePercent());
    }

    @Test
    void changeIsNullWhenThePreviousCloseIsZero() {
        // Guards the division; a zero close is bad upstream data, not a 100% move.
        Quote q = quote(50.0, 0.0);
        assertNull(q.change());
        assertNull(q.changePercent());
    }
}
