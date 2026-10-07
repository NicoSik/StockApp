package stockapp.service;

import okhttp3.HttpUrl;
import stockapp.enablebanking.EnableBankingClient;
import stockapp.enablebanking.EnableBankingException;
import stockapp.repo.AccountRepo;
import stockapp.repo.BankLinkRepo;
import stockapp.repo.InstrumentRepo;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bank account balances through Enable Banking: linking a bank with BankID,
 * and syncing its accounts into the holdings total.
 *
 * <p>Each linked bank becomes an ordinary account, "DNB (bank)", with one
 * holding per bank account valued at its balance - the same shape eToro's cash
 * already has. So the total, the account cards and the value history need no
 * special case for it.
 *
 * <p>Linking has two halves with the user's browser in between:
 * {@link #connect} returns the bank's login page, and the bank sends the
 * browser back with a code. If the callback URL is this app's own, that lands
 * on {@link #complete}; if Enable Banking only accepts its own site as the
 * callback, the user pastes the address they land on and it goes through
 * {@link #completeFromUrl}.
 */
public final class BankSyncService {

    /** The broker name on every linked bank account. */
    public static final String BROKER = "BANK";

    /** How long a started bank login can be finished. */
    private static final Duration PENDING_TTL = Duration.ofMinutes(30);
    /** PSD2 caps unattended access at 180 days, whatever a bank would allow. */
    private static final Duration MAX_CONSENT = Duration.ofDays(180);
    /** Requested consent stays this far short of the limit, so clock drift cannot exceed it. */
    private static final Duration CONSENT_MARGIN = Duration.ofHours(1);

    /** The code and state a bank sends back after BankID. */
    record Callback(String code, String state) {
    }

    /** A bank login that was started here and not finished yet, and the consent it asked for. */
    private record Pending(String bank, String country, Instant requestedUntil) {
    }

    /** One link as the page shows it. */
    public record LinkStatus(String bank, Instant validUntil, boolean expired, int accounts) {
    }

    /** What one bank's sync stored. */
    public record Result(String bank, String accountName, int accounts, BigDecimal totalNok, List<String> notes) {
    }

    private final EnableBankingClient client;
    private final BankLinkRepo links;
    private final AccountRepo accounts;
    private final InstrumentRepo instruments;
    private final FxService fx;
    private final String country;
    private final String redirectUrl;
    private final Cache<String, Pending> pending = new Cache<>();

    public BankSyncService(EnableBankingClient client, BankLinkRepo links, AccountRepo accounts,
                           InstrumentRepo instruments, FxService fx, String country, String redirectUrl) {
        this.client = client;
        this.links = links;
        this.accounts = accounts;
        this.instruments = instruments;
        this.fx = fx;
        this.country = country;
        this.redirectUrl = redirectUrl;
    }

    public boolean configured() {
        return client.configured();
    }

    public String redirectUrl() {
        return redirectUrl;
    }

    /** The banks that can be linked in the configured country. */
    public List<EnableBankingClient.Bank> banks() {
        return client.banks(country);
    }

    /** Every link, with whether its consent has run out. */
    public List<LinkStatus> links() {
        Instant now = Instant.now();
        return links.all().stream()
                .map(link -> new LinkStatus(link.bank(), link.validUntil(), link.expired(now), link.accounts().size()))
                .toList();
    }

    /** Starts the BankID login at {@code bankName}. Returns the page to send the browser to. */
    public String connect(String bankName) {
        EnableBankingClient.Bank bank = banks().stream()
                .filter(candidate -> candidate.name().equals(bankName))
                .findFirst()
                .orElseThrow(() -> new EnableBankingException(
                        "Enable Banking has no bank called \"" + bankName + "\" in " + country + "."));
        String state = UUID.randomUUID().toString();
        Instant until = consentUntil(Instant.now(), bank.maximumConsentSeconds());
        pending.put(state, new Pending(bank.name(), bank.country() == null ? country : bank.country(), until),
                PENDING_TTL);
        return client.startAuthorization(bank.name(), country, redirectUrl, state, until);
    }

    /**
     * Finishes a link from the code and state the bank sent back, stores it,
     * and syncs it straight away.
     */
    public Result complete(String code, String state) {
        Pending started = state == null ? null : pending.peek(state);
        if (started == null) {
            // Also what stops a callback this app did not start from linking anything.
            throw new EnableBankingException("This bank login was not started here, or it is more than "
                    + PENDING_TTL.toMinutes() + " minutes old. Start again from Connect a bank.");
        }
        pending.invalidate(state);
        EnableBankingClient.Session session = client.createSession(code);
        if (session.validUntil() == null) {
            // The consent's end should come back with the session; if it does
            // not, the end that was asked for is the best estimate there is.
            session = new EnableBankingClient.Session(session.id(), started.requestedUntil(), session.accounts());
        }
        String sessionId = session.id();
        links.save(started.bank(), started.country(), session);
        BankLinkRepo.Link link = links.all().stream()
                .filter(candidate -> candidate.sessionId().equals(sessionId))
                .findFirst()
                .orElseThrow();
        return sync(link);
    }

    /** {@link #complete} from the address the bank sent the browser to, pasted by the user. */
    public Result completeFromUrl(String pastedUrl) {
        Callback callback = callback(pastedUrl);
        return complete(callback.code(), callback.state());
    }

    /**
     * Syncs every link whose consent is still valid. An expired one is skipped
     * and named in the notes, and so is one that fails: a refusal at one bank
     * must not cost the others their balances.
     */
    public List<Result> syncAll() {
        List<Result> results = new ArrayList<>();
        Instant now = Instant.now();
        for (BankLinkRepo.Link link : links.all()) {
            if (link.expired(now)) {
                results.add(new Result(link.bank(), accountName(link.bank()), 0, BigDecimal.ZERO,
                        List.of("The consent for " + link.bank() + " expired on " + link.validUntil()
                                + ". Connect the bank again to keep its balances current.")));
                continue;
            }
            try {
                results.add(sync(link));
            } catch (RuntimeException e) {
                results.add(new Result(link.bank(), accountName(link.bank()), 0, BigDecimal.ZERO,
                        List.of(link.bank() + " could not be synced: " + e.getMessage())));
            }
        }
        return results;
    }

    /** Reads every account of one link and writes today's snapshot of their balances. */
    private Result sync(BankLinkRepo.Link link) {
        List<AccountRepo.StoredHolding> holdings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (EnableBankingClient.LinkedAccount linked : link.accounts()) {
            EnableBankingClient.Balance balance = EnableBankingClient.pickBalance(client.balances(linked.uid()));
            String name = accountName(linked);
            if (balance == null) {
                notes.add(name + ": the bank reported no balance.");
                continue;
            }
            String currency = balance.currency() != null ? balance.currency()
                    : linked.currency() != null ? linked.currency() : "NOK";
            BigDecimal valueNok = fx.toNok(balance.amount(), currency);
            if (valueNok == null) {
                notes.add(name + ": no exchange rate for " + currency + ", so it was left out.");
                continue;
            }
            // price_source NONE: a balance is what it is, there is nothing to re-price.
            InstrumentRepo.Instrument instrument = instruments.upsertExternal(
                    BROKER, linked.uid(), null, name, currency, "OTHER", "NONE");
            holdings.add(new AccountRepo.StoredHolding(
                    instrument.id(), null, instrument.name(), currency, "OTHER", "NONE", true,
                    balance.amount(), null, valueNok, null, null));
            total = total.add(valueNok);
        }

        if (holdings.isEmpty()) {
            throw new EnableBankingException(link.bank() + " returned no balances for the linked accounts.");
        }
        AccountRepo.Account account = accounts.ensureAccount(accountName(link.bank()), BROKER, "LINKED");
        accounts.writeSnapshot(account.id(), LocalDate.now(), "Enable Banking", null, holdings);
        return new Result(link.bank(), account.name(), holdings.size(), total, notes);
    }

    // --------------------------------------------------------------- helpers

    /**
     * Reads the code and state out of the address the bank sent the browser
     * to - a full URL or just its query string.
     *
     * @throws EnableBankingException when the bank reported an error, or there is no code
     */
    static Callback callback(String pasted) {
        String text = pasted == null ? "" : pasted.trim();
        if (!text.contains("://")) {
            text = "http://localhost/" + (text.startsWith("?") ? text : "?" + text);
        }
        HttpUrl url = HttpUrl.parse(text);
        if (url == null) {
            throw new EnableBankingException("That is not the address the bank sent you to.");
        }
        String error = url.queryParameter("error");
        if (error != null) {
            String description = url.queryParameter("error_description");
            throw new EnableBankingException("The bank did not grant access: "
                    + (description != null ? description : error));
        }
        String code = url.queryParameter("code");
        if (code == null || code.isBlank()) {
            throw new EnableBankingException("That address has no code in it. Paste the whole address "
                    + "of the page the bank sent you to after BankID.");
        }
        return new Callback(code, url.queryParameter("state"));
    }

    /** How long to ask the bank for: its own maximum, never beyond 180 days, minus a margin. */
    static Instant consentUntil(Instant now, Long bankMaximumSeconds) {
        Duration length = bankMaximumSeconds == null
                ? MAX_CONSENT
                : Duration.ofSeconds(Math.min(bankMaximumSeconds, MAX_CONSENT.toSeconds()));
        return now.plus(length).minus(CONSENT_MARGIN);
    }

    /** A readable name for a bank account: its own name, else the end of its number. */
    static String accountName(EnableBankingClient.LinkedAccount account) {
        if (account.name() != null && !account.name().isBlank()) {
            return account.name().trim();
        }
        String id = account.identifier();
        if (id != null && id.length() >= 4) {
            return "Account ···" + id.substring(id.length() - 4);
        }
        return "Bank account";
    }

    /** The Ticker account a bank's balances are filed under; distinct from that bank's file imports. */
    private static String accountName(String bank) {
        return bank + " (bank)";
    }
}
