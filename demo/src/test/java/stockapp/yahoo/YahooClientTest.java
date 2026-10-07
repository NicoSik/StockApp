package stockapp.yahoo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.NavigableMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Daily closes from the chart endpoint, dated in the exchange's own time zone. Invented prices. */
class YahooClientTest {

    private static JsonObject chart(String meta, String timestamps, String closes) {
        return JsonParser.parseString("""
                {"chart": {"result": [{
                    "meta": %s,
                    "timestamp": [%s],
                    "indicators": {"quote": [{"close": [%s]}]}
                }], "error": null}}
                """.formatted(meta, timestamps, closes)).getAsJsonObject();
    }

    @Test
    void closesAreKeyedByTradingDate() {
        // 07:00 UTC is 09:00 in Oslo: the opening timestamp of each session.
        NavigableMap<LocalDate, Double> closes = YahooClient.parseDailyCloses(chart(
                "{\"exchangeTimezoneName\": \"Europe/Oslo\", \"gmtoffset\": 7200}",
                "1788764400, 1788850800",
                "201.0, 205.8"));
        assertEquals(List.of(LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-08")),
                List.copyOf(closes.keySet()));
        assertEquals(205.8, closes.get(LocalDate.parse("2026-09-08")));
    }

    @Test
    void theExchangeTimeZoneDecidesTheDateNotUtc() {
        // A fund priced at midnight in Dublin is stamped 23:00 UTC the day
        // before. Read in UTC, every close would land one day early.
        NavigableMap<LocalDate, Double> closes = YahooClient.parseDailyCloses(chart(
                "{\"exchangeTimezoneName\": \"Europe/Dublin\", \"gmtoffset\": 3600}",
                "1788822000, 1788908400",
                "152.31, 152.90"));
        assertEquals(List.of(LocalDate.parse("2026-09-08"), LocalDate.parse("2026-09-09")),
                List.copyOf(closes.keySet()));
    }

    @Test
    void theOffsetIsUsedWhenTheZoneNameIsMissing() {
        NavigableMap<LocalDate, Double> closes = YahooClient.parseDailyCloses(chart(
                "{\"gmtoffset\": 3600}", "1788822000", "152.31"));
        assertEquals(LocalDate.parse("2026-09-08"), closes.firstKey());
    }

    @Test
    void daysWithoutACloseAreSkipped() {
        // Funds report null on days they did not price.
        NavigableMap<LocalDate, Double> closes = YahooClient.parseDailyCloses(chart(
                "{\"exchangeTimezoneName\": \"Europe/Oslo\"}",
                "1788764400, 1788850800, 1788937200",
                "201.0, null, 207.4"));
        assertEquals(List.of(LocalDate.parse("2026-09-07"), LocalDate.parse("2026-09-09")),
                List.copyOf(closes.keySet()));
    }

    @Test
    void anErrorOrEmptyResponseYieldsNothing() {
        JsonObject error = JsonParser.parseString(
                "{\"chart\": {\"result\": null, \"error\": {\"code\": \"Not Found\"}}}").getAsJsonObject();
        assertTrue(YahooClient.parseDailyCloses(error).isEmpty());
        assertTrue(YahooClient.parseDailyCloses(new JsonObject()).isEmpty());
    }
}
