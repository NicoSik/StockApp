package stockapp.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortfolioServiceTest {

    @Test
    void wholeShareCountsDoNotBecomeScientificNotation() {
        // Regression guard: stripTrailingZeros() alone turns 10.000000 into
        // 1E+1, which is valid JSON but reads as a bug in the response body.
        assertEquals("10", PortfolioService.tidyQuantity(new BigDecimal("10.000000")).toPlainString());
        assertEquals("100", PortfolioService.tidyQuantity(new BigDecimal("100.000000")).toPlainString());
        assertEquals("1000", PortfolioService.tidyQuantity(new BigDecimal("1000.00")).toPlainString());
    }

    @Test
    void fractionalShareCountsKeepThePrecisionTheyNeed() {
        assertEquals("1.5", PortfolioService.tidyQuantity(new BigDecimal("1.500000")).toPlainString());
        assertEquals("0.001", PortfolioService.tidyQuantity(new BigDecimal("0.001000")).toPlainString());
        assertEquals("2.25", PortfolioService.tidyQuantity(new BigDecimal("2.250000")).toPlainString());
    }

    @Test
    void zeroAndNullAreSafe() {
        assertEquals("0", PortfolioService.tidyQuantity(new BigDecimal("0.000000")).toPlainString());
        assertEquals("0", PortfolioService.tidyQuantity(null).toPlainString());
    }

    @Test
    void valueIsNeverAltered() {
        BigDecimal tidied = PortfolioService.tidyQuantity(new BigDecimal("10.000000"));
        assertEquals(0, tidied.compareTo(new BigDecimal("10")));
    }
}
