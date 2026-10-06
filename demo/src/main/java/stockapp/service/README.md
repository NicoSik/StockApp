# service

The logic of both halves of the app, between `web/` and `repo/`.

- **Paper trading:** `MarketData` (quotes, charts, sparklines), `AlpacaSync`
  (asset list and daily bars into the database), `PortfolioService`,
  `AlertService`, `Scheduler`.
- **Holdings:** `ImportService` (file → preview → snapshot), `EtoroSyncService`,
  `FxService`, and `Valuation` + `ValuationService`.

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
