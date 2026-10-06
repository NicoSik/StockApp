# alpaca

Client for Alpaca's two APIs: the trading API (account, assets, market clock)
and the market-data API (snapshots and bars). It powers the **paper-trading
half only** — watchlists, charts and the simulated portfolio.

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
