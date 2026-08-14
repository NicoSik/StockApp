package stockapp.web;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void rejectsAnEmptyBody() {
        assertThrows(Json.BadRequest.class, () -> Json.parseObject(null));
        assertThrows(Json.BadRequest.class, () -> Json.parseObject("   "));
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(Json.BadRequest.class, () -> Json.parseObject("{oops"));
    }

    @Test
    void rejectsAJsonValueThatIsNotAnObject() {
        assertThrows(Json.BadRequest.class, () -> Json.parseObject("[1, 2, 3]"));
        assertThrows(Json.BadRequest.class, () -> Json.parseObject("\"AAPL\""));
    }

    @Test
    void requiredStringsAreTrimmed() {
        JsonObject body = Json.parseObject("{\"symbol\": \"  aapl \"}");
        assertEquals("aapl", Json.requireString(body, "symbol"));
    }

    @Test
    void aMissingOrBlankRequiredStringIsRejected() {
        assertThrows(Json.BadRequest.class,
                () -> Json.requireString(Json.parseObject("{}"), "symbol"));
        assertThrows(Json.BadRequest.class,
                () -> Json.requireString(Json.parseObject("{\"symbol\":\"  \"}"), "symbol"));
    }

    @Test
    void quantitiesAcceptBothNumbersAndStrings() {
        // A form posts "1.5"; a script posts 1.5. Both must work.
        assertEquals(new BigDecimal("1.5"),
                Json.requirePositiveDecimal(Json.parseObject("{\"quantity\": 1.5}"), "quantity"));
        assertEquals(new BigDecimal("1.5"),
                Json.requirePositiveDecimal(Json.parseObject("{\"quantity\": \"1.5\"}"), "quantity"));
    }

    @Test
    void nonPositiveQuantitiesAreRejected() {
        assertThrows(Json.BadRequest.class,
                () -> Json.requirePositiveDecimal(Json.parseObject("{\"quantity\": 0}"), "quantity"));
        assertThrows(Json.BadRequest.class,
                () -> Json.requirePositiveDecimal(Json.parseObject("{\"quantity\": -5}"), "quantity"));
    }

    @Test
    void nonNumericQuantitiesAreRejected() {
        assertThrows(Json.BadRequest.class,
                () -> Json.requirePositiveDecimal(Json.parseObject("{\"quantity\": \"lots\"}"), "quantity"));
        assertThrows(Json.BadRequest.class,
                () -> Json.requirePositiveDecimal(Json.parseObject("{}"), "quantity"));
    }

    @Test
    void enumeratedFieldsAreNormalisedToUpperCase() {
        assertEquals("BUY", Json.requireOneOf(Json.parseObject("{\"side\":\"buy\"}"), "side", "BUY", "SELL"));
        assertEquals("SELL", Json.requireOneOf(Json.parseObject("{\"side\":\"SeLl\"}"), "side", "BUY", "SELL"));
    }

    @Test
    void valuesOutsideTheAllowedSetAreRejectedWithAHelpfulMessage() {
        Json.BadRequest error = assertThrows(Json.BadRequest.class,
                () -> Json.requireOneOf(Json.parseObject("{\"side\":\"HODL\"}"), "side", "BUY", "SELL"));
        assertTrue(error.getMessage().contains("BUY"), "the message should list the allowed values");
    }

    @Test
    void optionalStringsReturnNullWhenAbsent() {
        assertNull(Json.optString(Json.parseObject("{}"), "note"));
    }

    @Test
    void nullsAreSerialisedRatherThanDropped() {
        // The client distinguishes "field absent" from "field is null"; Gson
        // omits nulls by default, which would erase that distinction.
        record Sample(String symbol, Double change) {
        }
        assertTrue(Json.write(new Sample("AAPL", null)).contains("\"change\":null"));
    }
}
