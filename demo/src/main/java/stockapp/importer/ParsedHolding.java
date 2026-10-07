package stockapp.importer;

import java.math.BigDecimal;

/**
 * One row of a broker export, normalised.
 *
 * <p>Everything is optional except name, quantity and NOK value, because the
 * brokers disagree about what they provide: Nordnet gives an average cost per
 * share but no ticker, DNB's report gives a ticker but no per-holding cost
 * basis, and DNB's other export gives an ISIN. Fields that a broker does not
 * supply stay null rather than being filled with a plausible-looking zero.
 *
 * @param ticker    the broker's ticker, when it gives one
 * @param isin      the ISIN, when the file or the user gives one - the one
 *                  identifier that names an instrument exactly
 *
 * @param lastPrice the broker's own last price, in {@code currency}. This is
 *                  what {@link stockapp.market.InstrumentResolver} verifies a
 *                  resolved symbol against, so it is the single most valuable
 *                  column in either file.
 */
public record ParsedHolding(String name,
                            String ticker,
                            String isin,
                            String currency,
                            BigDecimal quantity,
                            BigDecimal avgCost,
                            BigDecimal lastPrice,
                            BigDecimal valueNative,
                            BigDecimal valueNok) {
}
