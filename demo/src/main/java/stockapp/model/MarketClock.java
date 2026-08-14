package stockapp.model;

/**
 * US equities session state, mirrored from Alpaca's clock endpoint.
 *
 * @param session one of {@code PRE}, {@code OPEN}, {@code AFTER}, {@code CLOSED} -
 *                derived locally, since Alpaca only reports the regular session
 * @param nextOpen  ISO-8601 instant of the next regular open
 * @param nextClose ISO-8601 instant of the next regular close
 */
public record MarketClock(boolean isOpen, String session, String nextOpen, String nextClose, long serverTime) {
}
