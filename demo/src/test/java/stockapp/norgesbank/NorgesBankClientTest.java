package stockapp.norgesbank;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Norges Bank CSV parsing, and the unit-multiplier trap in particular. */
class NorgesBankClientTest {

    /** A verbatim response shape: semicolon-delimited, with UNIT_MULT. */
    private static final String CSV = String.join("\n",
            "FREQ;Frequency;BASE_CUR;Base Currency;QUOTE_CUR;Quote Currency;TENOR;Tenor;DECIMALS;"
                    + "CALCULATED;UNIT_MULT;Unit Multiplier;COLLECTION;Collection Indicator;TIME_PERIOD;OBS_VALUE",
            "B;Business;DKK;Danish krone;NOK;Norwegian krone;SP;Spot;2;false;2;Hundreds;C;ECB;2026-08-14;146.24",
            "B;Business;USD;US dollar;NOK;Norwegian krone;SP;Spot;4;false;0;Units;C;ECB;2026-08-14;9.4515",
            "B;Business;EUR;Euro;NOK;Norwegian krone;SP;Spot;4;false;0;Units;C;ECB;2026-08-14;10.9325",
            "B;Business;SEK;Swedish krona;NOK;Norwegian krone;SP;Spot;2;false;2;Hundreds;C;ECB;2026-08-14;99.4");

    @Test
    void perUnitCurrenciesAreTakenAtFaceValue() {
        Map<String, BigDecimal> rates = NorgesBankClient.parse(CSV);
        assertEquals(0, rates.get("USD").compareTo(new BigDecimal("9.4515")));
        assertEquals(0, rates.get("EUR").compareTo(new BigDecimal("10.9325")));
    }

    @Test
    void currenciesQuotedPerHundredAreNormalisedToPerUnit() {
        // The whole point of this test. Norges Bank quotes SEK and DKK per 100
        // units, flagged by UNIT_MULT=2. Taking 99.4 literally would value a
        // Swedish holding at a hundred times its worth.
        Map<String, BigDecimal> rates = NorgesBankClient.parse(CSV);
        assertEquals(0, rates.get("SEK").compareTo(new BigDecimal("0.994")),
                "100 SEK = 99.4 NOK, so 1 SEK = 0.994 NOK");
        assertEquals(0, rates.get("DKK").compareTo(new BigDecimal("1.4624")),
                "100 DKK = 146.24 NOK, so 1 DKK = 1.4624 NOK");
    }

    @Test
    void normalisedSekRateReproducesTheBrokerReportedValue() {
        // Cross-check against a real holding: 35 260 units of a SEK instrument
        // at 0,0252 is 888,552 SEK, which the broker converted to 882,93 NOK.
        BigDecimal sek = NorgesBankClient.parse(CSV).get("SEK");
        BigDecimal nok = new BigDecimal("888.552").multiply(sek);
        assertTrue(nok.subtract(new BigDecimal("882.93")).abs().compareTo(new BigDecimal("1.00")) < 0,
                "expected roughly 882.93 NOK but got " + nok);
    }

    @Test
    void columnsAreLocatedByNameNotPosition() {
        // The response carries a dozen metadata columns whose order is not
        // contractual; a reordered header must still parse.
        String reordered = String.join("\n",
                "OBS_VALUE;UNIT_MULT;BASE_CUR;QUOTE_CUR",
                "9.4515;0;USD;NOK",
                "99.4;2;SEK;NOK");
        Map<String, BigDecimal> rates = NorgesBankClient.parse(reordered);
        assertEquals(0, rates.get("USD").compareTo(new BigDecimal("9.4515")));
        assertEquals(0, rates.get("SEK").compareTo(new BigDecimal("0.994")));
    }

    @Test
    void oneUnparseableRowDoesNotLoseTheOthers() {
        String withJunk = String.join("\n",
                "BASE_CUR;QUOTE_CUR;UNIT_MULT;OBS_VALUE",
                "USD;NOK;0;9.4515",
                "GBP;NOK;0;not-a-number",
                "EUR;NOK;0;10.9325");
        Map<String, BigDecimal> rates = NorgesBankClient.parse(withJunk);
        assertEquals(2, rates.size());
        assertNull(rates.get("GBP"));
    }

    @Test
    void emptyOrHeaderOnlyInputYieldsNoRates() {
        assertTrue(NorgesBankClient.parse("").isEmpty());
        assertTrue(NorgesBankClient.parse("BASE_CUR;OBS_VALUE").isEmpty());
    }

    // ------------------------------------------------------------- history

    /** Two business days for two currencies, as a date-range request returns them. */
    private static final String HISTORY = String.join("\n",
            "BASE_CUR;QUOTE_CUR;UNIT_MULT;TIME_PERIOD;OBS_VALUE",
            "USD;NOK;0;2026-10-02;9.6494",
            "USD;NOK;0;2026-10-05;9.6015",
            "SEK;NOK;2;2026-10-02;101.20",
            "SEK;NOK;2;2026-10-05;100.80");

    @Test
    void historyKeepsEveryDatePerCurrency() {
        Map<String, NavigableMap<LocalDate, BigDecimal>> history = NorgesBankClient.parseHistory(HISTORY);
        assertEquals(List.of(LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-05")),
                List.copyOf(history.get("USD").keySet()));
        assertEquals(0, history.get("USD").get(LocalDate.parse("2026-10-05")).compareTo(new BigDecimal("9.6015")));
    }

    @Test
    void historyNormalisesPerHundredQuotesToo() {
        Map<String, NavigableMap<LocalDate, BigDecimal>> history = NorgesBankClient.parseHistory(HISTORY);
        assertEquals(0, history.get("SEK").get(LocalDate.parse("2026-10-02")).compareTo(new BigDecimal("1.012")));
    }

    @Test
    void historySkipsRowsWithoutADate() {
        // The latest-rates response format, which a history caller must not
        // mistake for a dated series.
        assertTrue(NorgesBankClient.parseHistory(CSV.replace("TIME_PERIOD", "PERIOD")).isEmpty());
    }
}
