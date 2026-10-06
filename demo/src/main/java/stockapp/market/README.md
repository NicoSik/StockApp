# market

Turns a row from a broker export — a name, a ticker, sometimes an ISIN — into a
verified, priceable symbol. The exports rarely identify an instrument exactly,
so this is inference, and the rules below are what make it safe.

## What's inside

**`InstrumentResolver`**
- `resolve(ticker, isin, name, currency, expectedPrice)` — finds the symbol for one export row, trying the ISIN, then the ticker, then the name, and checks its live price against the export's. Returns a `Resolution`: confirmed, needs review, or unresolved.
- `describe(symbol)` — the live quote for a symbol the user picked by hand.
- `queriesFor(name)` — what to search for: the name as given, then without a trailing share-class letter.
- `candidates(matches, suffix)` — drops derivatives, then puts listings on the currency's exchange first.
- `Resolution` — the outcome; `usable()` is true only when confirmed.

## Not here

- Talking to Yahoo directly beyond search and quotes — that is `yahoo/`.
- Remembering a match. Aliases are stored by `repo/InstrumentRepo`, and only
  once a mapping is settled.

## Rules

- **Currency pins the exchange.** NOK means Oslo Børs, SEK Stockholm.
- **Derivatives are filtered out.** An option can trade near its underlying and
  pass a price check, so options are excluded by type and symbol shape.
- **The export's own price is the proof.** If the live price differs from the
  broker's by more than the tolerance, the match is refused and handed to the
  user. A plausible wrong match is worse than none.
- **Funds are told apart by NAV.** A fund search returns several share classes;
  value ÷ units picks the right one. An ISIN, when given, is exact.
