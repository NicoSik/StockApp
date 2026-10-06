# model

Plain data shared across layers — mostly immutable records — and `Money`, the
rounding rules for every amount the API returns.

## What's inside

- **`Money`** — `money(value)` rounds an amount to two decimals; `percent(part, whole)` is part ÷ whole × 100, zero on a zero denominator.
- **`Range`** — the chart ranges 1D–5Y. `parse(label)`, and per range the Alpaca `timeframe()`, `lookback()`, `cacheTtl()` and whether bars are daily.
- **`Quote`** — one symbol's price, previous close and session stats. `Quote.of(…)` fills in change and percent.
- **`Candle`**, **`Candles`** — one OHLCV bar; a chart series with its baseline and source.
- **`Spark`** — a downsampled sparkline. **`MarketClock`** — US session state.
- **`Stock`**, **`Watchlist`** — a tradable symbol; a named, ordered list of them.
- **`Holding`**, **`PortfolioSummary`**, **`TradeRecord`** — a paper position valued live, the whole paper portfolio, one simulated fill.
- **`Alert`** — a one-shot price alert; `pending()` until it fires.

## Not here

- Logic beyond small derived values. Records that only one layer uses live
  next to that layer, e.g. `AccountRepo.Snapshot`, `Valuation.Totals`.

## Rules

- **Money is `BigDecimal`**, rounded through `Money.money()` and
  `Money.percent()`. Market-data prices (`Quote`, `Candle`) arrive as `double`
  and are converted at that boundary; doubles are never summed.
- `null` means "unknown" and is serialised as such. A missing previous close
  is not a flat day, and the UI renders the two differently.
