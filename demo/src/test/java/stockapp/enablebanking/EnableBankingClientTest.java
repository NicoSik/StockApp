package stockapp.enablebanking;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Enable Banking client against a local fake of the API, so the JWT, the
 * requests and the parsing are checked without a network or a real account.
 * Response bodies follow the shapes in Enable Banking's API reference.
 */
class EnableBankingClientTest {

    private static final String APP_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @TempDir
    Path dir;

    private KeyPair keys;
    private HttpServer server;
    private EnableBankingClient client;

    /** Last request seen per path: "METHOD path" to body, and its Authorization header. */
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final Map<String, String> authorization = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        Path keyFile = dir.resolve(APP_ID + ".pem");
        Files.writeString(keyFile, pem("PRIVATE KEY", keys.getPrivate().getEncoded()));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = new EnableBankingClient(new OkHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort(), APP_ID, keyFile);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(String method, String path, int status, String body) {
        server.createContext(path, exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            bodies.put(key, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.put(key, String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            int code = exchange.getRequestMethod().equals(method) ? status : 405;
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    // ------------------------------------------------------------------ jwt

    @Test
    void theJwtIsSignedWithTheApplicationKey() throws Exception {
        Instant now = Instant.parse("2026-10-07T12:00:00Z");
        String[] parts = client.jwt(now).split("\\.");
        assertEquals(3, parts.length);

        JsonObject header = decode(parts[0]);
        assertEquals("RS256", header.get("alg").getAsString());
        assertEquals("JWT", header.get("typ").getAsString());
        assertEquals(APP_ID, header.get("kid").getAsString());

        JsonObject payload = decode(parts[1]);
        assertEquals("enablebanking.com", payload.get("iss").getAsString());
        assertEquals("api.enablebanking.com", payload.get("aud").getAsString());
        long iat = payload.get("iat").getAsLong();
        assertEquals(now.getEpochSecond(), iat);
        assertTrue(payload.get("exp").getAsLong() - iat <= 3600, "Enable Banking allows at most an hour");

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])), "signature must verify");
    }

    @Test
    void aPkcs1KeyIsRejectedWithAnExplanation() throws IOException {
        Path pkcs1 = dir.resolve("old.pem");
        Files.writeString(pkcs1, pem("RSA PRIVATE KEY", new byte[] {1, 2, 3}));
        EnableBankingException error = assertThrows(EnableBankingException.class,
                () -> new EnableBankingClient(new OkHttpClient(), "http://127.0.0.1:1", APP_ID, pkcs1).jwt(Instant.now()));
        assertTrue(error.getMessage().contains("PKCS#8"), error.getMessage());
    }

    // ----------------------------------------------------------------- calls

    @Test
    void banksAreListedForACountry() {
        respond("GET", "/aspsps", 200, """
                {"aspsps": [
                  {"name": "DNB", "country": "NO", "psu_types": ["personal", "business"],
                   "maximum_consent_validity": 15552000},
                  {"name": "SpareBank 1 SR-Bank", "country": "NO", "psu_types": ["personal"]}
                ]}""");
        List<EnableBankingClient.Bank> banks = client.banks("NO");
        assertEquals(List.of("DNB", "SpareBank 1 SR-Bank"), banks.stream().map(EnableBankingClient.Bank::name).toList());
        assertEquals(15552000L, banks.get(0).maximumConsentSeconds());
        assertNull(banks.get(1).maximumConsentSeconds());
    }

    @Test
    void startingAnAuthorisationSendsTheBankStateAndRedirect() {
        respond("POST", "/auth", 200, """
                {"url": "https://tilisy.enablebanking.com/welcome?sessionid=abc", "authorization_id": "x"}""");
        String url = client.startAuthorization("DNB", "NO", "http://localhost:9090/api/holdings/banks/callback",
                "state-123", Instant.parse("2027-04-05T12:00:00Z"));

        assertEquals("https://tilisy.enablebanking.com/welcome?sessionid=abc", url);
        JsonObject sent = JsonParser.parseString(bodies.get("POST /auth")).getAsJsonObject();
        assertEquals("DNB", sent.getAsJsonObject("aspsp").get("name").getAsString());
        assertEquals("NO", sent.getAsJsonObject("aspsp").get("country").getAsString());
        assertEquals("state-123", sent.get("state").getAsString());
        assertEquals("personal", sent.get("psu_type").getAsString());
        assertEquals("http://localhost:9090/api/holdings/banks/callback", sent.get("redirect_url").getAsString());
        assertTrue(sent.getAsJsonObject("access").get("valid_until").getAsString().startsWith("2027-04-05T12:00"));
        assertTrue(authorization.get("POST /auth").startsWith("Bearer "), "every call carries the JWT");
    }

    @Test
    void aSessionListsTheLinkedAccounts() {
        respond("POST", "/sessions", 200, """
                {"session_id": "0b1d4c4e-6a1c-4b8e-9f4b-1c2d3e4f5a6b",
                 "accounts": [
                   {"uid": "07cc67f4-45d6-494b-adac-09b5cbc7e2b5", "name": "Brukskonto",
                    "account_id": {"iban": "NO9386011117947"}, "currency": "NOK",
                    "cash_account_type": "CACC"},
                   {"uid": "2b4e7a1c-0000-4000-8000-000000000002", "name": null,
                    "account_id": {"other": {"identification": "12345678903"}}, "currency": "NOK"}
                 ],
                 "access": {"valid_until": "2027-04-05T12:00:00.000000+00:00"}}""");
        EnableBankingClient.Session session = client.createSession("the-code");

        assertEquals("0b1d4c4e-6a1c-4b8e-9f4b-1c2d3e4f5a6b", session.id());
        assertEquals(Instant.parse("2027-04-05T12:00:00Z"), session.validUntil());
        assertEquals(2, session.accounts().size());
        assertEquals("Brukskonto", session.accounts().get(0).name());
        assertEquals("NO9386011117947", session.accounts().get(0).identifier());
        assertEquals("12345678903", session.accounts().get(1).identifier(), "no IBAN: the bank's own number");
        assertEquals("the-code", JsonParser.parseString(bodies.get("POST /sessions"))
                .getAsJsonObject().get("code").getAsString());
    }

    @Test
    void balancesAreReadWithTheirType() {
        respond("GET", "/accounts/07cc67f4-45d6-494b-adac-09b5cbc7e2b5/balances", 200, """
                {"balances": [
                  {"name": "Booked", "balance_amount": {"currency": "NOK", "amount": "12345.67"},
                   "balance_type": "CLBD", "reference_date": "2026-10-06"},
                  {"name": "Available", "balance_amount": {"currency": "NOK", "amount": "62345.67"},
                   "balance_type": "ITAV"}
                ]}""");
        List<EnableBankingClient.Balance> balances = client.balances("07cc67f4-45d6-494b-adac-09b5cbc7e2b5");
        assertEquals(2, balances.size());
        assertEquals("CLBD", balances.get(0).type());
        assertEquals(0, new BigDecimal("12345.67").compareTo(balances.get(0).amount()));
        assertEquals(LocalDate.parse("2026-10-06"), balances.get(0).referenceDate());
    }

    @Test
    void aRejectedRequestExplainsItself() {
        respond("GET", "/aspsps", 401, "{\"message\": \"Invalid JWT\"}");
        EnableBankingException error = assertThrows(EnableBankingException.class, () -> client.banks("NO"));
        assertTrue(error.getMessage().contains("ENABLE_BANKING_APP_ID"), error.getMessage());
        assertEquals(401, error.status());
    }

    // -------------------------------------------------------- choosing one

    @Test
    void theBookedBalanceIsPreferredOverTheAvailableOne() {
        // "Available" can include a credit line: money that is not yours.
        List<EnableBankingClient.Balance> balances = List.of(
                balance("ITAV", "62345.67"), balance("CLBD", "12345.67"), balance("ITBD", "12400.00"));
        assertEquals("ITBD", EnableBankingClient.pickBalance(balances).type(), "the latest booked balance");
    }

    @Test
    void anAvailableBalanceIsUsedOnlyWhenNothingIsBooked() {
        assertEquals("CLAV", EnableBankingClient.pickBalance(List.of(balance("CLAV", "10"), balance("OTHR", "11"))).type());
        assertNull(EnableBankingClient.pickBalance(List.of()));
    }

    // ---------------------------------------------------------------- helpers

    private static EnableBankingClient.Balance balance(String type, String amount) {
        return new EnableBankingClient.Balance(type, new BigDecimal(amount), "NOK", null);
    }

    private static JsonObject decode(String part) {
        return JsonParser.parseString(new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
