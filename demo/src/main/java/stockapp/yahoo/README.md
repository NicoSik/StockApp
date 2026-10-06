# yahoo

Prices for every holding: Oslo Børs, Stockholm and US listings, ETFs, and
Norwegian mutual funds, each in its own currency. Also symbol search, used by
the resolver and the reconcile screen.

## What's inside

**`YahooClient`**
- `quote(symbol)` — live price, currency and previous close; empty when Yahoo has nothing.
- `search(query)` — candidate symbols for a name, ticker or ISIN.
- `suffixForCurrency(currency)` — the exchange suffix a currency implies: `.OL` for NOK, `.ST` for SEK, `.CO` for DKK, none otherwise.
- `Quote`, `Match` — a price, and one search candidate.

## Not here

- Deciding *which* symbol a holding is. That is `market/InstrumentResolver`;
  this client only answers questions about a symbol it is given.
- Caching. Callers cache — `service/ValuationService` keeps prices for a minute
  and refreshes them in the background.

## Rules

- **The endpoint is unofficial** — undocumented, unsupported, and it can change
  without notice. When it breaks, holdings fall back to the value their broker
  last reported. Nothing may depend on it being up.
- Quotes come from the chart endpoint one symbol at a time; the batch quote
  endpoint now demands a session crumb.
- Requests need a browser-shaped `User-Agent` or Yahoo rejects them.
