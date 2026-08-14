package stockapp.model;

/** A tradable instrument as stored in the {@code stock} table. */
public record Stock(int id, String symbol, String company, String market) {
}
