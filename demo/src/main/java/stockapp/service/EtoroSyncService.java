package stockapp.service;

import stockapp.Config;
import stockapp.etoro.EtoroClient;
import stockapp.etoro.EtoroException;
import stockapp.repo.AccountRepo;
import stockapp.repo.InstrumentRepo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pulls the eToro portfolio and writes it as a snapshot.
 *
 * <p>Deliberately the same destination as a file import: one dated snapshot per
 * sync, replacing that day's if it already exists. That keeps a single
 * valuation path for every account regardless of how the data arrived, and the
 * value history accumulates the same way.
 *
 * <p>What is <em>not</em> done here is as important as what is. eToro holdings
 * are never re-priced from a ticker, and their instruments are never run
 * through {@link stockapp.market.InstrumentResolver}. An eToro account can hold
 * leveraged CFDs, short positions and copy portfolios alongside ordinary
 * shares, and none of the first three can be valued from a share price. eToro
 * reports what each position is worth; that figure is taken as authoritative
 * and only the currency conversion is ours to do.
 */
public final class EtoroSyncService {

    public static final String ACCOUNT_NAME = "eToro";
    public static final String BROKER = "ETORO";

    private final EtoroClient etoro;
    private final AccountRepo accounts;
    private final InstrumentRepo instruments;
    private final FxService fx;

    public EtoroSyncService(EtoroClient etoro, AccountRepo accounts, InstrumentRepo instruments, FxService fx) {
        this.etoro = etoro;
        this.accounts = accounts;
        this.instruments = instruments;
        this.fx = fx;
    }

    public record Result(int accountId,
                         String accountName,
                         int snapshotId,
                         int positions,
                         String accountCurrency,
                         BigDecimal totalNok,
                         BigDecimal cashNok,
                         int unnamed,
                         List<String> notes) {
    }

    public boolean configured() {
        return EtoroClient.configured();
    }

    /**
     * Fetches and stores the current portfolio.
     *
     * @throws EtoroException with a message fit to show the user
     */
    public Result sync() {
        EtoroClient.Portfolio portfolio = etoro.portfolio(Config.ETORO_DEMO);
        String currency = portfolio.accountCurrency();
        List<String> notes = new ArrayList<>();

        BigDecimal rate = fx.rate(currency);
        if (rate == null) {
            throw new EtoroException("No exchange rate available for " + currency
                    + ", so the eToro holdings cannot be converted to NOK.");
        }

        // One batched call turns eToro's numeric ids into names.
        Map<Long, EtoroClient.InstrumentInfo> named = etoro.instruments(
                portfolio.positions().stream().map(EtoroClient.Position::instrumentId).toList());

        List<AccountRepo.StoredHolding> holdings = new ArrayList<>();
        int unnamed = 0;
        int leveraged = 0;
        int shorts = 0;

        for (EtoroClient.Position position : portfolio.positions()) {
            BigDecimal valueAccount = position.valueAccount();
            if (valueAccount == null) {
                notes.add("Skipped instrument " + position.instrumentId() + ": eToro reported no value for it.");
                continue;
            }
            BigDecimal valueNok = valueAccount.multiply(rate).setScale(2, RoundingMode.HALF_UP);

            EtoroClient.InstrumentInfo info = named.get(position.instrumentId());
            if (info == null) {
                unnamed++;
            }
            String ticker = info == null ? null : info.ticker();
            String name = info != null && info.name() != null && !info.name().isBlank()
                    ? info.name()
                    : (ticker != null ? ticker : "eToro instrument " + position.instrumentId());

            boolean isLeveraged = position.leverage() != null
                    && position.leverage().compareTo(BigDecimal.ONE) > 0;
            boolean isShort = "SHORT".equals(position.direction());
            if (isLeveraged) {
                leveraged++;
            }
            if (isShort) {
                shorts++;
            }

            // price_source NONE, deliberately: the value came from eToro and
            // must not be recomputed from a share price, which would be wrong
            // for anything leveraged, short, or a copy portfolio.
            InstrumentRepo.Instrument instrument = instruments.upsertExternal(
                    BROKER,
                    String.valueOf(position.instrumentId()),
                    ticker,
                    name,
                    position.assetCurrency() == null ? currency : position.assetCurrency(),
                    kindOf(info, isLeveraged),
                    "NONE");

            BigDecimal avgCost = position.openRate();
            holdings.add(new AccountRepo.StoredHolding(
                    instrument.id(), instrument.symbol(), instrument.name(),
                    position.assetCurrency() == null ? currency : position.assetCurrency(),
                    instrument.kind(), "NONE", true,
                    position.units() == null ? BigDecimal.ZERO : position.units(),
                    avgCost,
                    valueNok,
                    position.leverage(),
                    position.direction()));
        }

        // Uninvested cash is part of what the account is worth, so it is carried
        // as its own line rather than quietly dropped.
        BigDecimal cashAccount = portfolio.totals().availableCash();
        BigDecimal cashNok = BigDecimal.ZERO;
        if (cashAccount != null && cashAccount.signum() > 0) {
            cashNok = cashAccount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            InstrumentRepo.Instrument cash = instruments.upsertExternal(
                    BROKER, "CASH", null, "Cash (" + currency + ")", currency, "OTHER", "NONE");
            holdings.add(new AccountRepo.StoredHolding(
                    cash.id(), null, cash.name(), currency, "OTHER", "NONE", true,
                    cashAccount, null, cashNok, null, null));
        }

        if (holdings.isEmpty()) {
            throw new EtoroException("eToro returned no positions and no cash for this account.");
        }

        if (leveraged > 0) {
            notes.add(leveraged + " leveraged position(s) — valued by eToro, not re-priced here.");
        }
        if (shorts > 0) {
            notes.add(shorts + " short position(s).");
        }
        if (unnamed > 0) {
            notes.add(unnamed + " instrument(s) could not be named; they are stored under their eToro id.");
        }
        if (Config.ETORO_DEMO) {
            notes.add("This is the eToro demo account, not real money.");
        }

        // A demo account is flagged simulated so its practice balance is shown
        // but never counted: eToro reports a demo portfolio exactly like a real
        // one, cash included, and left unflagged it multiplies a net worth.
        AccountRepo.Account account = accounts.ensureAccount(
                Config.ETORO_DEMO ? ACCOUNT_NAME + " (demo)" : ACCOUNT_NAME,
                BROKER, "LINKED", Config.ETORO_DEMO);

        BigDecimal reportedTotal = portfolio.totals().totalValue() == null
                ? null
                : portfolio.totals().totalValue().multiply(rate).setScale(2, RoundingMode.HALF_UP);

        int snapshotId = accounts.writeSnapshot(
                account.id(), LocalDate.now(), "eToro API", reportedTotal, holdings);

        BigDecimal total = holdings.stream()
                .map(AccountRepo.StoredHolding::valueNok)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new Result(account.id(), account.name(), snapshotId, portfolio.positions().size(),
                currency, total, cashNok, unnamed, notes);
    }

    /**
     * Leverage is the giveaway: an ordinary holding is 1, a CFD is more.
     *
     * <p>Otherwise the category comes from eToro's numeric
     * {@code instrumentTypeID}. Only the values worth distinguishing here are
     * mapped; anything else falls through to OTHER rather than being guessed
     * at, since the kind is cosmetic and a wrong label is worse than a vague one.
     */
    private static String kindOf(EtoroClient.InstrumentInfo info, boolean leveraged) {
        if (leveraged) {
            return "OTHER";
        }
        if (info == null) {
            return "OTHER";
        }
        return switch (info.typeId()) {
            case 5 -> "STOCK";
            case 6 -> "ETF";
            case 10 -> "CRYPTO";
            default -> "OTHER";
        };
    }
}
