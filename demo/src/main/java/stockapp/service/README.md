# service

The logic of both halves of the app, between `web/` and `repo/`.

- **Paper trading:** `MarketData` (quotes, charts, sparklines), `AlpacaSync`
  (asset list and daily bars into the database), `PortfolioService`,
  `AlertService`.
- **Holdings:** `ImportService` (file → preview → snapshot), `EtoroSyncService`,
  `FxService`, `Valuation` + `ValuationService`, and `HoldingsHistorySync`.
- **Both:** `Scheduler` runs the background jobs for each half.

## What's inside

Paper trading:
- **`MarketData`** — `quotes(symbols)` and `quote(symbol)` (cached, batched), `candles(stock, range)` (chart series, falling back to stored daily bars), `sparklines(symbols)`, `clock()`, `downsample(bars, n)`.
- **`AlpacaSync`** — `syncAssets()` refreshes the asset table; `backfillDaily(stock, from)` stores daily bars; `ensureDailyCoverage(stock, from)` fetches only what is missing.
- **`PortfolioService`** — `summary()`, `buy` / `sell` (fill at the last trade), `history(range)` (value curve rebuilt from the trade log), `positionFor(stock)`, `trades(limit)`, `reset()`.
- **`AlertService`** — `evaluate()` fires every pending alert whose threshold was crossed.

Holdings:
- **`ImportService`** — `preview(filename, bytes)` parses and resolves without writing; `previewFunds(account, broker, funds)` does the same for hand-entered funds; `commit(previewId, overrides, skip)` writes the snapshot and remembers settled matches. `aliasKeys(isin, ticker, name)` is the order a row's labels are remembered and looked up in.
- **`EtoroSyncService`** — `sync()` writes the live eToro portfolio as a snapshot; `configured()`.
- **`FxService`** — `toNok(amount, currency)`, `rate(currency)`, `latestRates()` (cached for an hour, stored rates as fallback); `rateHistory(from)` reads stored dated rates, `fetchRateHistory(…)` fetches and stores them.
- **`Valuation`** — `compute(…)` produces every figure on the holdings page; `value(holding, …)` values one holding, live or as reported; `history(…)` values every day since the first import at that day's close and rate; `isPriceable(holding)` says which holdings get a market price. No I/O; tested.
- **`ValuationService`** — `valueEverything()` feeds `Valuation` the cached prices and starts a background refresh; `history()` feeds it stored closes and rates.
- **`HoldingsHistorySync`** — `refreshInBackground()` / `refresh()` fetch the closes and rates the history is missing; `missing(need, stored, today)` decides which date ranges those are.

Shared:
- **`Scheduler`** — `start()` / `close()`: alerts every minute; once a day, end-of-day bars, the asset sync, and the holdings day (an eToro sync when keys are set, then the history refresh). `runHoldingsDay(…)` keeps a failed eToro sync from skipping the refresh.
- **`Cache`** — a small TTL map: `get(key, ttl, loader)`, `peek`, `peekStale`, `put`, `invalidate`.

## Not here

- HTTP concerns — status codes, request parsing (`web/`).
- Talking to an external API directly; that goes through its client package.

## Rules

- **The two halves never mix.** Simulated money must not reach a real total,
  and a simulated eToro account is excluded from every aggregate.
- **Never make a page wait on the network.** Serve what is cached, however old,
  and refresh in the background.
- **Keep sums pure.** Arithmetic worth testing goes in a class with no I/O, the
  way `Valuation` is separate from `ValuationService`.
- A scheduled job catches everything it throws. An uncaught exception cancels a
  `ScheduledExecutorService` task forever, silently.
