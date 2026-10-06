# market

Turns a row from a broker export — a name, a ticker, sometimes an ISIN — into a
verified, priceable symbol. The exports rarely identify an instrument exactly,
so this is inference, and the rules below are what make it safe.

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
