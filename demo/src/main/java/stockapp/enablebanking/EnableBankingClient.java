package stockapp.enablebanking;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/**
 * Client for Enable Banking, a licensed Open Banking aggregator: bank account
 * balances, with the account holder's consent given through BankID at their
 * own bank.
 *
 * <p>Enable Banking gives "restricted production" access free of charge to
 * accounts the application owner links themselves, for non-commercial use.
 * That is this app's case: one person, their own accounts.
 *
 * <p>Every call carries a JWT signed with the application's private key - the
 * {@code .pem} file the Enable Banking control panel downloads when the
 * application is registered. The key never leaves this machine; only the
 * signed token does.
 *
 * <p>The flow: {@link #startAuthorization} returns the bank's login page; the
 * bank sends the browser back with a {@code code}; {@link #createSession}
 * turns that into a session that can read the linked accounts until its
 * consent expires (at most 180 days for unattended access); {@link #balances}
 * reads each account.
 */
public final class EnableBankingClient {

    private static final MediaType JSON = MediaType.get("application/json");

    /** Enable Banking accepts tokens living at most an hour. */
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(1);
    /** A cached token is replaced this long before it expires. */
    private static final Duration TOKEN_MARGIN = Duration.ofMinutes(5);

    /**
     * Balance types, best first. Booked balances come before available ones:
     * "available" can include a credit line, which is money that is not yours.
     */
    private static final List<String> BALANCE_PREFERENCE = List.of(
            "ITBD", "CLBD", "XPCD", "OPBD", "ITAV", "CLAV", "OPAV", "FWAV", "PRCD", "VALU", "INFO", "OTHR");

    /** A bank Enable Banking can connect to. */
    public record Bank(String name, String country, Long maximumConsentSeconds) {
    }

    /**
     * One bank account the user linked.
     *
     * @param identifier its IBAN, or the bank's own account number when there is none
     */
    public record LinkedAccount(String uid, String name, String identifier, String currency) {
    }

    /** Read access to the linked accounts, until {@code validUntil}. */
    public record Session(String id, Instant validUntil, List<LinkedAccount> accounts) {
    }

    /** One balance of an account. {@code type} is an ISO 20022 code such as CLBD. */
    public record Balance(String type, BigDecimal amount, String currency, LocalDate referenceDate) {
    }

    private final OkHttpClient http;
    private final String apiUrl;
    private final String appId;
    private final Path keyFile;

    private PrivateKey key;
    private String token;
    private Instant tokenExpires = Instant.EPOCH;

    /**
     * @param shared  the app-wide client; see {@code AlpacaClient}
     * @param apiUrl  Enable Banking's API, {@code https://api.enablebanking.com}
     * @param appId   the application ID from the control panel
     * @param keyFile the application's private key, as downloaded
     */
    public EnableBankingClient(OkHttpClient shared, String apiUrl, String appId, Path keyFile) {
        this.http = shared.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .build();
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.appId = appId;
        this.keyFile = keyFile;
    }

    /** True when an application ID and a readable key file are set; the feature hides itself otherwise. */
    public boolean configured() {
        return appId != null && !appId.isBlank() && keyFile != null && Files.isReadable(keyFile);
    }

    // ----------------------------------------------------------------- calls

    /** The banks that can be connected in a country, for personal customers. */
    public List<Bank> banks(String country) {
        HttpUrl url = HttpUrl.parse(apiUrl + "/aspsps").newBuilder()
                .addQueryParameter("country", country)
                .addQueryParameter("psu_type", "personal")
                .build();
        JsonObject json = parse(execute(new Request.Builder().url(url).get()));
        List<Bank> banks = new ArrayList<>();
        JsonArray list = json.has("aspsps") && json.get("aspsps").isJsonArray() ? json.getAsJsonArray("aspsps") : new JsonArray();
        for (JsonElement element : list) {
            JsonObject bank = element.getAsJsonObject();
            banks.add(new Bank(text(bank, "name"), text(bank, "country"),
                    bank.has("maximum_consent_validity") && !bank.get("maximum_consent_validity").isJsonNull()
                            ? bank.get("maximum_consent_validity").getAsLong() : null));
        }
        return banks;
    }

    /**
     * Starts the bank's login. Returns the URL to send the browser to; the
     * bank sends it back to {@code redirectUrl} with a {@code code} and the
     * same {@code state}.
     */
    public String startAuthorization(String bank, String country, String redirectUrl, String state,
                                     Instant validUntil) {
        JsonObject access = new JsonObject();
        access.addProperty("valid_until", validUntil.toString());
        JsonObject aspsp = new JsonObject();
        aspsp.addProperty("name", bank);
        aspsp.addProperty("country", country);
        JsonObject body = new JsonObject();
        body.add("access", access);
        body.add("aspsp", aspsp);
        body.addProperty("state", state);
        body.addProperty("redirect_url", redirectUrl);
        body.addProperty("psu_type", "personal");

        JsonObject json = parse(execute(new Request.Builder().url(apiUrl + "/auth")
                .post(RequestBody.create(body.toString(), JSON))));
        String url = text(json, "url");
        if (url == null || url.isBlank()) {
            throw new EnableBankingException("Enable Banking did not return a bank login page.");
        }
        return url;
    }

    /** Turns the {@code code} the bank returned into a session over the linked accounts. */
    public Session createSession(String code) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        JsonObject json = parse(execute(new Request.Builder().url(apiUrl + "/sessions")
                .post(RequestBody.create(body.toString(), JSON))));

        List<LinkedAccount> accounts = new ArrayList<>();
        JsonArray list = json.has("accounts") && json.get("accounts").isJsonArray() ? json.getAsJsonArray("accounts") : new JsonArray();
        for (JsonElement element : list) {
            JsonObject account = element.getAsJsonObject();
            accounts.add(new LinkedAccount(text(account, "uid"), text(account, "name"),
                    identifier(account), text(account, "currency")));
        }
        JsonObject access = json.has("access") && json.get("access").isJsonObject() ? json.getAsJsonObject("access") : null;
        return new Session(text(json, "session_id"), instant(access == null ? null : text(access, "valid_until")), accounts);
    }

    /** Every balance the bank reports for one account. */
    public List<Balance> balances(String accountUid) {
        JsonObject json = parse(execute(new Request.Builder()
                .url(apiUrl + "/accounts/" + accountUid + "/balances").get()));
        List<Balance> balances = new ArrayList<>();
        JsonArray list = json.has("balances") && json.get("balances").isJsonArray() ? json.getAsJsonArray("balances") : new JsonArray();
        for (JsonElement element : list) {
            JsonObject balance = element.getAsJsonObject();
            JsonObject amount = balance.has("balance_amount") && balance.get("balance_amount").isJsonObject()
                    ? balance.getAsJsonObject("balance_amount") : null;
            if (amount == null || text(amount, "amount") == null) {
                continue;
            }
            try {
                String date = text(balance, "reference_date");
                balances.add(new Balance(text(balance, "balance_type"), new BigDecimal(text(amount, "amount")),
                        text(amount, "currency"), date == null ? null : LocalDate.parse(date)));
            } catch (NumberFormatException | DateTimeParseException e) {
                // One unreadable balance must not lose the others.
            }
        }
        return balances;
    }

    /** The balance to count, by {@link #BALANCE_PREFERENCE}; null when there is none. */
    public static Balance pickBalance(List<Balance> balances) {
        return balances.stream()
                .min(Comparator.comparingInt(b -> {
                    int rank = BALANCE_PREFERENCE.indexOf(b.type());
                    return rank < 0 ? BALANCE_PREFERENCE.size() : rank;
                }))
                .orElse(null);
    }

    // ------------------------------------------------------------------- jwt

    /** A token valid for an hour from {@code now}, signed with the application key. */
    String jwt(Instant now) {
        String header = base64("{\"typ\":\"JWT\",\"alg\":\"RS256\",\"kid\":\"" + appId + "\"}");
        String payload = base64("{\"iss\":\"enablebanking.com\",\"aud\":\"api.enablebanking.com\",\"iat\":"
                + now.getEpochSecond() + ",\"exp\":" + now.plus(TOKEN_LIFETIME).getEpochSecond() + "}");
        String signingInput = header + "." + payload;
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey());
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new EnableBankingException("Could not sign the Enable Banking token: " + e.getMessage(), e);
        }
    }

    private synchronized String bearer() {
        Instant now = Instant.now();
        if (token == null || now.isAfter(tokenExpires.minus(TOKEN_MARGIN))) {
            token = jwt(now);
            tokenExpires = now.plus(TOKEN_LIFETIME);
        }
        return token;
    }

    /**
     * Reads the PKCS#8 key the control panel downloads. An older
     * {@code BEGIN RSA PRIVATE KEY} (PKCS#1) file is refused with the command
     * that converts it, rather than with a parsing error.
     */
    private synchronized PrivateKey privateKey() {
        if (key != null) {
            return key;
        }
        if (keyFile == null || !Files.isReadable(keyFile)) {
            throw new EnableBankingException("Enable Banking is not configured: set ENABLE_BANKING_KEY_FILE in .env "
                    + "to the .pem file the control panel downloaded.");
        }
        String pem;
        try {
            pem = Files.readString(keyFile);
        } catch (IOException e) {
            throw new EnableBankingException("Could not read " + keyFile + ": " + e.getMessage(), e);
        }
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw new EnableBankingException(keyFile + " is a PKCS#1 key; Java needs PKCS#8. Convert it with: "
                    + "openssl pkcs8 -topk8 -nocrypt -in " + keyFile + " -out key-pkcs8.pem");
        }
        String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        try {
            key = KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            return key;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new EnableBankingException(keyFile + " is not a PKCS#8 RSA private key: " + e.getMessage(), e);
        }
    }

    private static String base64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ http

    private String execute(Request.Builder builder) {
        Request request = builder
                .header("Authorization", "Bearer " + bearer())
                .header("Accept", "application/json")
                .build();
        try (Response response = http.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (response.code() == 401 || response.code() == 403) {
                throw new EnableBankingException("Enable Banking rejected the request (HTTP " + response.code()
                        + "). Check ENABLE_BANKING_APP_ID matches the key in ENABLE_BANKING_KEY_FILE, and that "
                        + "the application is activated by linking your accounts in the control panel.",
                        response.code());
            }
            if (response.code() == 429) {
                throw new EnableBankingException("Rate limit reached. Banks allow unattended reads about four "
                        + "times a day; try again later.", response.code());
            }
            if (!response.isSuccessful()) {
                throw new EnableBankingException("Enable Banking returned HTTP " + response.code() + ": "
                        + (text.length() <= 300 ? text : text.substring(0, 300) + "..."), response.code());
            }
            return text;
        } catch (IOException e) {
            throw new EnableBankingException("Could not reach Enable Banking: " + e.getMessage(), e);
        }
    }

    private static JsonObject parse(String text) {
        JsonElement parsed = JsonParser.parseString(text.isBlank() ? "{}" : text);
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
    }

    private static String text(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonPrimitive()
                ? parent.get(key).getAsString() : null;
    }

    private static String identifier(JsonObject account) {
        JsonObject id = account.has("account_id") && account.get("account_id").isJsonObject()
                ? account.getAsJsonObject("account_id") : null;
        if (id == null) {
            return null;
        }
        String iban = text(id, "iban");
        if (iban != null && !iban.isBlank()) {
            return iban;
        }
        JsonObject other = id.has("other") && id.get("other").isJsonObject() ? id.getAsJsonObject("other") : null;
        return other == null ? null : text(other, "identification");
    }

    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
