package stockapp.model;

import java.math.BigDecimal;

/**
 * One position, valued against the latest quote.
 *
 * <p>Quantities and money are {@link BigDecimal}: a paper portfolio still needs
 * to add up exactly, and binary floating point does not add up exactly.
 *
 * @param dayChange   change in this position's market value since the previous
 *                    close, null when no quote is available
 * @param weight      share of the portfolio's total market value, 0-100
 */
public record Holding(String symbol,
                      String company,
                      BigDecimal quantity,
                      BigDecimal avgCost,
                      BigDecimal costBasis,
                      BigDecimal lastPrice,
                      BigDecimal marketValue,
                      BigDecimal unrealizedPnl,
                      BigDecimal unrealizedPnlPercent,
                      BigDecimal realizedPnl,
                      BigDecimal dayChange,
                      BigDecimal dayChangePercent,
                      BigDecimal weight) {
}
