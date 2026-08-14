package stockapp.importer;

import java.math.BigDecimal;

/**
 * One row of a broker export, normalised.
 *
 * <p>Everything is optional except name, quantity and NOK value, because the
 * two supported brokers disagree about what they provide: Nordnet gives an
 * average cost per share but no ticker, DNB gives a ticker but no per-holding
 * cost basis. Fields that a broker does not supply stay null rather than being
 * filled with a plausible-looking zero.
 *
 * @param lastPrice the broker's own last price, in {@code currency}. This is
 *                  what {@link stockapp.market.InstrumentResolver} verifies a
 *                  resolved symbol against, so it is the single most valuable
 *                  column in either file.
 */
public record ParsedHolding(String name,
                            String ticker,
                            String currency,
                            BigDecimal quantity,
                            BigDecimal avgCost,
                            BigDecimal lastPrice,
                            BigDecimal valueNative,
                            BigDecimal valueNok) {
}
