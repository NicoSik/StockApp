package stockapp.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * The whole paper portfolio, valued live.
 *
 * @param totalValue    cash + market value of every holding
 * @param totalPnl      totalValue - startingCash, i.e. all-time performance
 * @param dayChange     sum of each holding's move since the previous close;
 *                      cash contributes nothing
 * @param stale         true when at least one holding could not be priced, so
 *                      the totals are best-effort rather than exact
 */
public record PortfolioSummary(int id,
                               String name,
                               BigDecimal cash,
                               BigDecimal startingCash,
                               BigDecimal marketValue,
                               BigDecimal totalValue,
                               BigDecimal totalPnl,
                               BigDecimal totalPnlPercent,
                               BigDecimal dayChange,
                               BigDecimal dayChangePercent,
                               BigDecimal realizedPnl,
                               List<Holding> holdings,
                               boolean stale) {
}
