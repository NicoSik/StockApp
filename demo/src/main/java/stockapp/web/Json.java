package stockapp.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** JSON encoding for responses and lenient reading of request bodies. */
public final class Json {

    /**
     * Nulls are serialised rather than dropped. A missing {@code change} field
     * and a null one mean different things to the client - "not sent" versus
     * "no previous close to compare against" - and the UI branches on it.
     *
     * <p>{@code java.time} types need explicit adapters. Gson serialises unknown
     * types by reflecting over their fields, and since Java 17 the platform
     * modules are strongly encapsulated, so reaching into {@code LocalDate}
     * throws rather than producing {@code {"year":2026,"month":8,...}} - which
     * would have been the wrong shape anyway. ISO-8601 strings are what the
     * browser wants.
     */
    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .registerTypeAdapter(LocalDate.class,
                    (JsonSerializer<LocalDate>) (date, type, ctx) ->
                            new JsonPrimitive(DateTimeFormatter.ISO_LOCAL_DATE.format(date)))
            .registerTypeAdapter(Instant.class,
                    (JsonSerializer<Instant>) (instant, type, ctx) -> new JsonPrimitive(instant.toString()))
            .registerTypeAdapter(LocalDateTime.class,
                    (JsonSerializer<LocalDateTime>) (dateTime, type, ctx) ->
                            new JsonPrimitive(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(dateTime)))
            .create();

    private Json() {
    }

    public static String write(Object value) {
        return GSON.toJson(value);
    }

    /** Raised for a malformed or missing field; the message reaches the user. */
    public static class BadRequest extends RuntimeException {
        public BadRequest(String message) {
            super(message);
        }
    }

    public static JsonObject parseObject(String body) {
        if (body == null || body.isBlank()) {
            throw new BadRequest("A JSON request body is required.");
        }
        try {
            var element = JsonParser.parseString(body);
            if (!element.isJsonObject()) {
                throw new BadRequest("The request body must be a JSON object.");
            }
            return element.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new BadRequest("The request body is not valid JSON.");
        }
    }

    public static String requireString(JsonObject json, String field) {
        String value = optString(json, field);
        if (value == null || value.isBlank()) {
            throw new BadRequest("\"" + field + "\" is required.");
        }
        return value.trim();
    }

    public static String optString(JsonObject json, String field) {
        return json.has(field) && json.get(field).isJsonPrimitive() ? json.get(field).getAsString() : null;
    }

    /**
     * Reads a positive decimal, accepting either a JSON number or a string, so
     * that {@code "1.5"} typed into a form works as well as {@code 1.5}.
     */
    public static BigDecimal requirePositiveDecimal(JsonObject json, String field) {
        if (!json.has(field) || json.get(field).isJsonNull()) {
            throw new BadRequest("\"" + field + "\" is required.");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(json.get(field).getAsString().trim());
        } catch (NumberFormatException | UnsupportedOperationException | IllegalStateException e) {
            throw new BadRequest("\"" + field + "\" must be a number.");
        }
        if (value.signum() <= 0) {
            throw new BadRequest("\"" + field + "\" must be greater than zero.");
        }
        return value;
    }

    /** Reads a field constrained to a fixed set, case-insensitively. */
    public static String requireOneOf(JsonObject json, String field, String... allowed) {
        String value = requireString(json, field).toUpperCase(Locale.ROOT);
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return candidate;
            }
        }
        throw new BadRequest("\"" + field + "\" must be one of " + String.join(", ", allowed) + ".");
    }
}
