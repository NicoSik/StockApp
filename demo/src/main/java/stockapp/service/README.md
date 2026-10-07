# service

The logic of both halves of the app, between `web/` and `repo/`.

- **Paper trading:** `MarketData` (quotes, charts, sparklines), `AlpacaSync`
  (asset list and daily bars into the database), `PortfolioService`,
  `AlertService`, `Scheduler`.
- **Holdings:** `ImportService` (file → preview → snapshot), `EtoroSyncService`,
  `FxService`, and `Valuation` + `ValuationService`.

## What's inside

Paper trading:
- **`MarketData`** — `quotes(symbols)` and `quote(symbol)` (cached, batched), `candles(stock, range)` (chart series, falling back to stored daily bars), `sparklines(symbols)`, `clock()`, `downsample(bars, n)`.
- **`AlpacaSync`** — `syncAssets()` refreshes the asset table; `backfillDaily(stock, from)` stores daily bars; `ensureDailyCoverage(stock, from)` fetches only what is missing.
- **`PortfolioService`** — `summary()`, `buy` / `sell` (fill at the last trade), `history(range)` (value curve rebuilt from the trade log), `positionFor(stock)`, `trades(limit)`, `reset()`.
- **`AlertService`** — `evaluate()` fires every pending alert whose threshold was crossed.
- **`Scheduler`** — `start()` / `close()`: alerts every minute; end-of-day bars and the asset sync once a day.

Holdings:
- **`ImportService`** — `preview(filename, bytes)` parses and resolves without writing; `previewFunds(account, broker, funds)` does the same for hand-entered funds; `commit(previewId, overrides, skip)` writes the snapshot and remembers settled matches.
- **`EtoroSyncService`** — `sync()` writes the live eToro portfolio as a snapshot; `configured()`.
- **`FxService`** — `toNok(amount, currency)`, `rate(currency)`, `latestRates()` (cached for an hour, stored rates as fallback).
- **`Valuation`** — `compute(…)` produces every figure on the holdings page; `value(holding, …)` values one holding, live or as reported. No I/O; tested.
- **`ValuationService`** — `valueEverything()` feeds `Valuation` the cached prices and starts a background refresh; `history()`.

Shared:
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
