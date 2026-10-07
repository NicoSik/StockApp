package stockapp.service;

import org.junit.jupiter.api.Test;
import stockapp.enablebanking.EnableBankingClient;
import stockapp.enablebanking.EnableBankingException;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parts of linking a bank that do not need a bank: callbacks, consent length, names. */
class BankSyncServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @Test
    void codeAndStateAreReadFromAPastedAddress() {
        BankSyncService.Callback callback = BankSyncService.callback(
                "https://enablebanking.com/?state=8f2c&code=0a7b-11&foo=bar");
        assertEquals("0a7b-11", callback.code());
        assertEquals("8f2c", callback.state());
    }

    @Test
    void aBareQueryStringWorksToo() {
        assertEquals("abc", BankSyncService.callback("code=abc&state=s1").code());
        assertEquals("s1", BankSyncService.callback("?code=abc&state=s1").state());
    }

    @Test
    void anErrorFromTheBankIsReportedAsSuch() {
        EnableBankingException error = assertThrows(EnableBankingException.class, () -> BankSyncService.callback(
                "http://localhost:9090/api/holdings/banks/callback?error=access_denied"
                        + "&error_description=User%20cancelled&state=s1"));
        assertTrue(error.getMessage().contains("User cancelled"), error.getMessage());
    }

    @Test
    void anAddressWithoutACodeIsRefused() {
        assertThrows(EnableBankingException.class, () -> BankSyncService.callback("https://enablebanking.com/"));
    }

    @Test
    void consentLastsAsLongAsTheBankAllowsUpTo180Days() {
        // A little short of the limit, so a clock difference cannot push the
        // request past what the bank accepts.
        assertEquals(NOW.plus(Duration.ofDays(180)).minus(Duration.ofHours(1)),
                BankSyncService.consentUntil(NOW, null));
        assertEquals(NOW.plus(Duration.ofDays(90)).minus(Duration.ofHours(1)),
                BankSyncService.consentUntil(NOW, Duration.ofDays(90).toSeconds()));
        assertEquals(NOW.plus(Duration.ofDays(180)).minus(Duration.ofHours(1)),
                BankSyncService.consentUntil(NOW, Duration.ofDays(365).toSeconds()));
    }

    @Test
    void anAccountIsNamedByItsBankNameOrItsNumber() {
        assertEquals("Brukskonto", BankSyncService.accountName(
                new EnableBankingClient.LinkedAccount("u1", "Brukskonto", "NO9386011117947", "NOK")));
        assertEquals("Account ···7947", BankSyncService.accountName(
                new EnableBankingClient.LinkedAccount("u1", " ", "NO9386011117947", "NOK")));
        assertEquals("Bank account", BankSyncService.accountName(
                new EnableBankingClient.LinkedAccount("u1", null, null, "NOK")));
    }
}
