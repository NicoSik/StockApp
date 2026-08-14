package stockapp.model;

import java.math.BigDecimal;

/**
 * A one-shot price alert.
 *
 * @param direction    {@code ABOVE} or {@code BELOW}
 * @param triggeredAt  ISO-8601 instant the alert fired, or null while pending
 */
public record Alert(int id,
                    String symbol,
                    String company,
                    String direction,
                    BigDecimal threshold,
                    String note,
                    String createdAt,
                    String triggeredAt,
                    BigDecimal triggeredPrice) {

    public boolean pending() {
        return triggeredAt == null;
    }
}
