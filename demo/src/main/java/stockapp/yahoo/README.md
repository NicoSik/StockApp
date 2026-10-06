# yahoo

Prices for every holding: Oslo Børs, Stockholm and US listings, ETFs, and
Norwegian mutual funds, each in its own currency. Also symbol search, used by
the resolver and the reconcile screen.

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
