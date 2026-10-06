# model

Plain data shared across layers — mostly immutable records — and `Money`, the
rounding rules for every amount the API returns.

## Not here

- Logic beyond small derived values. Records that only one layer uses live
  next to that layer, e.g. `AccountRepo.Snapshot`, `Valuation.Totals`.

## Rules

- **Money is `BigDecimal`**, rounded through `Money.money()` and
  `Money.percent()`. Market-data prices (`Quote`, `Candle`) arrive as `double`
  and are converted at that boundary; doubles are never summed.
- `null` means "unknown" and is serialised as such. A missing previous close
  is not a flat day, and the UI renders the two differently.
