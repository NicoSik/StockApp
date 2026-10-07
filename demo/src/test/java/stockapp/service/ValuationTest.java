package stockapp.service;

import org.junit.jupiter.api.Test;
import stockapp.repo.AccountRepo;
import stockapp.yahoo.YahooClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The holdings arithmetic: what counts towards the total, what gain is
 * measured against, and what is allowed to be priced live.
 */
class ValuationTest {

    /** 1 USD = 10 NOK, so expected figures can be worked out by eye. */
    private static final Valuation.Fx FX = (amount, currency) -> switch (currency) {
        case "NOK" -> amount;
        case "USD" -> amount.multiply(BigDecimal.TEN).setScale(2, RoundingMode.HALF_UP);
        default -> null;
    };

    private static final Map<String, YahooClient.Quote> QUOTES = Map.of(
            "AAPL", new YahooClient.Quote("AAPL", 120.0, "USD", "Apple", 0L, 110.0),
            "EQNR.OL", new YahooClient.Quote("EQNR.OL", 400.0, "NOK", "Equinor", 0L, null));

    private static final Valuation.Prices PRICES = QUOTES::get;

    // --- the portfolio most tests share --------------------------------------
    //
    //  Nordnet  real       AAPL 10 @ $100 cost, live at $120   -> 12 000 kr, cost 10 000
    //                      EQNR unverified, stored 1 500 kr    ->  1 500 kr, no cost
    //  DNB      real       fund, unpriced, stored 1 000 kr     ->  1 000 kr, account cost 800
    //  eToro    simulated  stored 50 000 kr                    ->  practice money
    //  Saxo     real       never imported

    private static final AccountRepo.Account NORDNET = account(1, "Nordnet", false);
    private static final AccountRepo.Account DNB = account(2, "DNB", false);
    private static final AccountRepo.Account ETORO_DEMO = account(3, "eToro", true);
    private static final AccountRepo.Account SAXO = account(4, "Saxo", false);

    private static final AccountRepo.StoredHolding AAPL = new AccountRepo.StoredHolding(
            10, "AAPL", "Apple", "USD", "STOCK", "YAHOO", true,
            new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("9000"));
    private static final AccountRepo.StoredHolding EQNR_UNVERIFIED = new AccountRepo.StoredHolding(
            11, "EQNR.OL", "Equinor", "NOK", "STOCK", "YAHOO", false,
            new BigDecimal("5"), null, new BigDecimal("1500"));
    private static final AccountRepo.StoredHolding DNB_FUND = new AccountRepo.StoredHolding(
            12, null, "DNB Global Indeks", "NOK", "FUND", null, false,
            new BigDecimal("3"), null, new BigDecimal("1000"));
    private static final AccountRepo.StoredHolding PRACTICE = new AccountRepo.StoredHolding(
            13, null, "Practice position", "NOK", "STOCK", null, false,
            new BigDecimal("1"), null, new BigDecimal("50000"));

    private static Valuation.Totals portfolio() {
        return Valuation.compute(List.of(
                new Valuation.AccountInput(NORDNET, snapshot(1, "2026-08-14", null),
                        List.of(AAPL, EQNR_UNVERIFIED)),
                new Valuation.AccountInput(DNB, snapshot(2, "2026-08-10", new BigDecimal("800")),
                        List.of(DNB_FUND)),
                new Valuation.AccountInput(ETORO_DEMO, snapshot(3, "2026-08-01", null),
                        List.of(PRACTICE)),
                new Valuation.AccountInput(SAXO, null, List.of())
        ), PRICES, FX, Map.of(), null);
    }

    // --- totals ---------------------------------------------------------------

    @Test
    void totalCountsRealMoneyOnly() {
        Valuation.Totals totals = portfolio();
        assertAmount("14500.00", totals.totalNok());
        assertAmount("50000.00", totals.simulatedNok());
        assertEquals(2, totals.accountCount(), "the demo account and the empty one are not counted");
        assertEquals(3, totals.holdingCount());
    }

    @Test
    void liveAndAsOfSplitTheRealTotal() {
        Valuation.Totals totals = portfolio();
        assertAmount("12000.00", totals.liveNok());
        assertAmount("2500.00", totals.asOfNok());
        assertAmount("82.76", totals.livePercent());
    }

    @Test
    void gainIsMeasuredOnlyWhereCostIsKnown() {
        // AAPL 12 000 - 10 000, plus DNB 1 000 - 800. EQNR has no cost, so its
        // 1 500 must not show up as profit.
        Valuation.Totals totals = portfolio();
        assertAmount("2200.00", totals.gainNok());
        assertAmount("10800.00", totals.costBasisNok());
    }

    @Test
    void oldestAsOfIgnoresSimulatedAndNeverImportedAccounts() {
        assertEquals(LocalDate.parse("2026-08-10"), portfolio().oldestAsOf());
    }

    @Test
    void dayChangeIsMeasuredOverTheHoldingsThatHaveAPreviousClose() {
        Valuation.Totals totals = portfolio();
        assertAmount("1000.00", totals.dayChangeNok());
        assertAmount("12000.00", totals.dayChangeBaseNok(), "not the 14 500 total");
    }

    @Test
    void combinedTableIsRealHoldingsLargestFirst() {
        List<Valuation.ValuedHolding> holdings = portfolio().holdings();
        assertEquals(List.of("Apple", "Equinor", "DNB Global Indeks"),
                holdings.stream().map(Valuation.ValuedHolding::name).toList());
        assertAmount("82.76", holdings.get(0).weight());
        assertAmount("10.34", holdings.get(1).weight());
        assertAmount("6.90", holdings.get(2).weight());
    }

    // --- accounts -------------------------------------------------------------

    @Test
    void accountGainUsesItsOwnHoldings() {
        Valuation.AccountValuation nordnet = account(portfolio(), "Nordnet");
        assertAmount("13500.00", nordnet.valueNok());
        assertAmount("10000.00", nordnet.costBasisNok());
        assertAmount("2000.00", nordnet.gainNok());
        assertAmount("20.00", nordnet.gainPercent());
        assertFalse(nordnet.costBasisReported());
    }

    @Test
    void portfolioLevelCostBasisIsUsedWhenNoRowHasOne() {
        // DNB reports Kostpris for the whole account and nothing per row.
        Valuation.AccountValuation dnb = account(portfolio(), "DNB");
        assertAmount("800.00", dnb.costBasisNok());
        assertAmount("200.00", dnb.gainNok());
        assertTrue(dnb.costBasisReported());
    }

    @Test
    void simulatedAccountIsListedButHasNoWeight() {
        Valuation.AccountValuation demo = account(portfolio(), "eToro");
        assertTrue(demo.simulated());
        assertAmount("50000.00", demo.valueNok());
        assertNull(demo.holdings().get(0).weight(), "null reads as a dash; zero would read as nothing");
    }

    @Test
    void neverImportedAccountIsListedEmpty() {
        Valuation.AccountValuation saxo = account(portfolio(), "Saxo");
        assertNull(saxo.asOf());
        assertAmount("0", saxo.valueNok());
        assertEquals(0, saxo.holdingCount());
    }

    @Test
    void noGainIsReportedWhenNothingIsMeasurable() {
        Valuation.Totals totals = Valuation.compute(List.of(
                new Valuation.AccountInput(NORDNET, snapshot(1, "2026-08-14", null), List.of(EQNR_UNVERIFIED))
        ), PRICES, FX, Map.of(), null);
        assertNull(totals.gainNok());
        assertNull(totals.costBasisNok());
    }

    // --- one holding ----------------------------------------------------------

    @Test
    void verifiedHoldingIsPricedLiveInNok() {
        Valuation.ValuedHolding aapl = Valuation.value(AAPL, "Nordnet", PRICES, FX);
        assertTrue(aapl.live());
        assertAmount("120", aapl.price());
        assertAmount("12000.00", aapl.valueNok());
        assertAmount("1000.00", aapl.dayChangeNok());
        assertAmount("9.09", aapl.dayChangePercent());
    }

    @Test
    void unverifiedMatchIsNeverPricedLive() {
        // A quote exists for EQNR.OL, but the mapping was never confirmed - an
        // unverified guess must not be allowed to move a real number.
        Valuation.ValuedHolding eqnr = Valuation.value(EQNR_UNVERIFIED, "Nordnet", PRICES, FX);
        assertFalse(eqnr.live());
        assertNull(eqnr.price());
        assertAmount("1500.00", eqnr.valueNok());
    }

    @Test
    void missingPriceFallsBackToTheStoredValue() {
        Valuation.ValuedHolding aapl = Valuation.value(AAPL, "Nordnet", symbol -> null, FX);
        assertFalse(aapl.live());
        assertAmount("9000.00", aapl.valueNok());
        assertNull(aapl.dayChangeNok(), "no price means no day change, not a flat day");
    }

    @Test
    void missingPreviousCloseLeavesDayChangeNull() {
        AccountRepo.StoredHolding verified = new AccountRepo.StoredHolding(
                11, "EQNR.OL", "Equinor", "NOK", "STOCK", "YAHOO", true,
                new BigDecimal("5"), null, new BigDecimal("1500"));
        Valuation.ValuedHolding eqnr = Valuation.value(verified, "Nordnet", PRICES, FX);
        assertTrue(eqnr.live());
        assertAmount("2000.00", eqnr.valueNok());
        assertNull(eqnr.dayChangeNok());
    }

    @Test
    void unknownCurrencyIsNotPricedLive() {
        AccountRepo.StoredHolding inYen = new AccountRepo.StoredHolding(
                14, "AAPL", "Apple", "JPY", "STOCK", "YAHOO", true,
                new BigDecimal("1"), null, new BigDecimal("700"));
        Valuation.ValuedHolding holding = Valuation.value(inYen, "Nordnet", PRICES, FX);
        assertFalse(holding.live(), "no rate means the stored value stands");
        assertAmount("700.00", holding.valueNok());
    }

    // --- history -------------------------------------------------------------
    //
    //  Nordnet imported twice: 10 AAPL on 1 Sep, 20 AAPL on 3 Sep, plus an
    //  unpriced fund worth 1 000 kr both times.
    //
    //              1 Sep   2 Sep   3 Sep   4 Sep
    //  AAPL close    100     110       -     120     (3 Sep missing: 110 carries)
    //  USD rate       10      10      11       -     (4 Sep missing: 11 carries)

    private static final LocalDate SEP_1 = LocalDate.parse("2026-09-01");
    private static final LocalDate SEP_2 = LocalDate.parse("2026-09-02");
    private static final LocalDate SEP_3 = LocalDate.parse("2026-09-03");
    private static final LocalDate SEP_4 = LocalDate.parse("2026-09-04");

    private static AccountRepo.StoredHolding aapl(String quantity) {
        return new AccountRepo.StoredHolding(10, "AAPL", "Apple", "USD", "STOCK", "YAHOO", true,
                new BigDecimal(quantity), null, new BigDecimal("9000"));
    }

    private static final AccountRepo.StoredHolding FUND = new AccountRepo.StoredHolding(
            20, null, "DNB Global Indeks", "NOK", "FUND", null, false,
            new BigDecimal("3"), null, new BigDecimal("1000"));

    private static final Map<String, NavigableMap<LocalDate, BigDecimal>> CLOSES = Map.of("AAPL", series(
            SEP_1, "100", SEP_2, "110", SEP_4, "120"));
    private static final Map<String, NavigableMap<LocalDate, BigDecimal>> RATES = Map.of("USD", series(
            SEP_1, "10", SEP_2, "10", SEP_3, "11"));

    private static Valuation.AccountHistory nordnetHistory() {
        return new Valuation.AccountHistory(NORDNET, List.of(
                new Valuation.DatedHoldings(SEP_1, List.of(aapl("10"), FUND)),
                new Valuation.DatedHoldings(SEP_3, List.of(aapl("20"), FUND))));
    }

    private static Map<LocalDate, BigDecimal> history(List<Valuation.AccountHistory> accounts,
                                                      Map<String, NavigableMap<LocalDate, BigDecimal>> closes) {
        Map<LocalDate, BigDecimal> byDay = new TreeMap<>();
        for (AccountRepo.ValuePoint point : Valuation.history(accounts, closes, RATES, SEP_4)) {
            byDay.put(point.date(), point.value());
        }
        return byDay;
    }

    @Test
    void historyValuesEachDayAtThatDaysCloseAndRate() {
        Map<LocalDate, BigDecimal> history = history(List.of(nordnetHistory()), CLOSES);
        assertAmount("11000.00", history.get(SEP_1), "10 x 100 USD x 10, plus the fund");
        assertAmount("12000.00", history.get(SEP_2), "10 x 110 USD x 10, plus the fund");
    }

    @Test
    void historyUsesEachImportFromItsOwnDate() {
        // 20 shares from 3 Sep: the close carries from 2 Sep, the rate is 3 Sep's.
        Map<LocalDate, BigDecimal> history = history(List.of(nordnetHistory()), CLOSES);
        assertAmount("25200.00", history.get(SEP_3), "20 x 110 USD x 11, plus the fund");
        assertAmount("27400.00", history.get(SEP_4), "20 x 120 USD x 11, plus the fund");
    }

    @Test
    void historyHasOnePointPerDayFromTheFirstImportToToday() {
        assertEquals(List.of(SEP_1, SEP_2, SEP_3, SEP_4),
                List.copyOf(history(List.of(nordnetHistory()), CLOSES).keySet()));
    }

    @Test
    void historyFallsBackToTheBrokerValueBeforeTheFirstClose() {
        // No AAPL close until 2 Sep: on 1 Sep the import's own 9 000 kr stands.
        Map<String, NavigableMap<LocalDate, BigDecimal>> late = Map.of("AAPL", series(SEP_2, "110"));
        assertAmount("10000.00", history(List.of(nordnetHistory()), late).get(SEP_1));
    }

    @Test
    void historyWithoutAnyPricesStillRunsFromTheImportToToday() {
        // Only an unpriced fund: flat at the broker's value, but still plotted
        // up to today rather than stopping at the import.
        Valuation.AccountHistory fundsOnly = new Valuation.AccountHistory(NORDNET, List.of(
                new Valuation.DatedHoldings(SEP_1, List.of(FUND))));
        Map<LocalDate, BigDecimal> history = history(List.of(fundsOnly), Map.of());
        assertEquals(List.of(SEP_1, SEP_4), List.copyOf(history.keySet()));
        assertAmount("1000.00", history.get(SEP_4));
    }

    @Test
    void historyCountsAnAccountFromItsFirstImportOnly() {
        Valuation.AccountHistory dnb = new Valuation.AccountHistory(DNB, List.of(
                new Valuation.DatedHoldings(SEP_3, List.of(DNB_FUND))));
        Map<LocalDate, BigDecimal> history = history(List.of(nordnetHistory(), dnb), CLOSES);
        assertAmount("12000.00", history.get(SEP_2), "DNB not imported yet");
        assertAmount("26200.00", history.get(SEP_3), "DNB's 1 000 kr from its import date");
    }

    @Test
    void historyLeavesSimulatedAccountsOut() {
        Valuation.AccountHistory demo = new Valuation.AccountHistory(ETORO_DEMO, List.of(
                new Valuation.DatedHoldings(SEP_1, List.of(PRACTICE))));
        Map<LocalDate, BigDecimal> history = history(List.of(nordnetHistory(), demo), CLOSES);
        assertAmount("11000.00", history.get(SEP_1));
    }

    @Test
    void historyIsEmptyBeforeAnyImport() {
        assertEquals(List.of(), Valuation.history(List.of(), CLOSES, RATES, SEP_4));
        assertEquals(List.of(), Valuation.history(
                List.of(new Valuation.AccountHistory(NORDNET, List.of())), CLOSES, RATES, SEP_4));
    }

    private static NavigableMap<LocalDate, BigDecimal> series(Object... dateValuePairs) {
        NavigableMap<LocalDate, BigDecimal> series = new TreeMap<>();
        for (int i = 0; i < dateValuePairs.length; i += 2) {
            series.put((LocalDate) dateValuePairs[i], new BigDecimal((String) dateValuePairs[i + 1]));
        }
        return series;
    }

    // --- helpers --------------------------------------------------------------

    private static AccountRepo.Account account(int id, String name, boolean simulated) {
        return new AccountRepo.Account(id, name, name.toUpperCase(), "BROKER", "NOK", simulated);
    }

    private static AccountRepo.Snapshot snapshot(int accountId, String asOf, BigDecimal reportedCost) {
        return new AccountRepo.Snapshot(accountId, accountId, LocalDate.parse(asOf), "test", null, reportedCost);
    }

    private static Valuation.AccountValuation account(Valuation.Totals totals, String name) {
        return totals.accounts().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertAmount(expected, actual, null);
    }

    private static void assertAmount(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                (message == null ? "" : message + ": ") + "expected " + expected + " but was " + actual);
    }
}
