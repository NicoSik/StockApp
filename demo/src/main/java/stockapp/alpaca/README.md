# alpaca

Client for Alpaca's two APIs: the trading API (account, assets, market clock)
and the market-data API (snapshots and bars). It powers the **paper-trading
half only** — watchlists, charts and the simulated portfolio.

## What's inside

**`AlpacaClient`**
- `clock()` — whether the US market is open, with pre-market and after-hours derived from New York time.
- `listAssets()` — every active US equity, for the search table.
- `snapshots(symbols)` — latest trade, daily bar and previous close per symbol, 100 per request.
- `bars(symbol, timeframe, start, end, adjusted)` — historical bars for one symbol, following pagination.
- `bars(symbols, …)` — the same for many symbols in one request; used for sparklines.
- `activeFeed()` — `sip` or `iex`, whichever this run ended up on.
- `toEpochMillis(iso)` — parses Alpaca's timestamps; 0 when unparseable.
- `Asset` — one row of the asset list.

**`AlpacaException`** — an upstream failure with its HTTP status. `isSubscriptionProblem()` spots the 403 that triggers the `iex` fallback.

## Not here

- Holdings. Alpaca is US equities only, and matching a Norwegian portfolio
  against it is actively wrong: it resolves `DNB` to Dun & Bradstreet. Holdings
  are priced through `yahoo/`.
- Orders. Nothing here ever calls Alpaca's order endpoint; trades are
  simulated in `repo/PortfolioRepo`.

## Rules

- Built from the shared `OkHttpClient` in `App`, with its own timeouts.
- Requests are batched — snapshots 100 symbols at a time — so the watchlist
  rail costs one upstream call per refresh, not one per row.
- An account not entitled to the `sip` feed gets a 403; the client drops to
  `iex` for the rest of the run instead of failing.
- Failures surface as `AlpacaException`, which `web/ErrorHandlers` maps to 502.
