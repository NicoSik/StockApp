package stockapp.model;

import java.math.BigDecimal;

/**
 * An executed simulated fill.
 *
 * @param amount cash moved: negative for a buy, positive for a sell
 */
public record TradeRecord(long id,
                          String symbol,
                          String company,
                          String side,
                          BigDecimal quantity,
                          BigDecimal price,
                          BigDecimal amount,
                          String executedAt,
                          String note) {
}
