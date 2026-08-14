package stockapp.service;

import stockapp.model.Alert;
import stockapp.model.Quote;
import stockapp.repo.AlertRepo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Evaluates pending price alerts against the latest quotes. */
public final class AlertService {

    private final AlertRepo alerts;
    private final MarketData marketData;

    public AlertService(AlertRepo alerts, MarketData marketData) {
        this.alerts = alerts;
        this.marketData = marketData;
    }

    /**
     * Checks every pending alert and fires the ones whose threshold has been
     * crossed.
     *
     * <p>An ABOVE alert fires when the last price is at or above the threshold,
     * BELOW when it is at or below. Each alert fires once: the repository's
     * conditional update makes a concurrent second evaluation a no-op.
     *
     * @return the alerts that fired on this pass, in the order they were checked
     */
    public List<Alert> evaluate() {
        List<AlertRepo.Pending> pending = alerts.pending();
        if (pending.isEmpty()) {
            return List.of();
        }

        Map<String, Quote> quotes = marketData.quotes(
                pending.stream().map(AlertRepo.Pending::symbol).distinct().toList());

        List<Alert> fired = new ArrayList<>();
        for (AlertRepo.Pending alert : pending) {
            Quote quote = quotes.get(alert.symbol().toUpperCase());
            if (quote == null) {
                continue;
            }
            BigDecimal price = BigDecimal.valueOf(quote.price());
            boolean crossed = "ABOVE".equals(alert.direction())
                    ? price.compareTo(alert.threshold()) >= 0
                    : price.compareTo(alert.threshold()) <= 0;

            if (crossed && alerts.markTriggered(alert.id(), price)) {
                alerts.find(alert.id()).ifPresent(fired::add);
                System.out.printf("[alert] %s crossed %s %s at %s%n",
                        alert.symbol(), alert.direction().toLowerCase(), alert.threshold(), price);
            }
        }
        return fired;
    }
}
