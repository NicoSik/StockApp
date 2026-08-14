package stockapp.model;

import java.util.List;

/** A named list of symbols. {@code symbols} is ordered by the user's arrangement. */
public record Watchlist(int id, String name, List<String> symbols) {
}
