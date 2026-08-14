# Roadmap

Ordered by value per unit of effort. Nothing here is required — the app is
complete as it stands.

## Next

**Live streaming instead of polling.** Alpaca has a WebSocket feed for trades
and quotes. Replacing the 15-second poll would make prices tick in real time
and cut request volume to near zero. Javalin has first-class WebSocket support,
so this is mostly a `MarketData` change plus a small client subscription. The
biggest win available.

**Multi-symbol compare.** The chart already normalises cleanly — overlay two or
three symbols as percentage change from the range start and the "which of these
actually outperformed" question answers itself. The renderer takes a single
series today; it would need a series list and a per-series colour.

**Drag to reorder the watchlist.** `PUT /api/watchlists/{id}/order` is already
implemented and tested by hand; nothing in the UI calls it yet. Pointer-based
reordering plus a keyboard alternative (`Alt+↑/↓`) would finish it.

**More than one watchlist.** The schema, repository and API all support many
lists. The rail only ever shows the first. A selector in the rail header and a
"new list" affordance is all that is missing.

## Later

**Browser notifications for alerts.** Alerts fire server-side every minute and
toast when the tab is open. `Notification.requestPermission()` plus a service
worker would surface them when it is not.

**Cost-basis lots.** Positions use a single weighted average cost. Real tax
accounting needs FIFO or specific-identification lots. The `trade` table already
records everything needed to compute them; it is a service-layer change.

**Dividend adjustment.** Daily bars are split-adjusted (`adjustment=split`).
Total-return charts want `adjustment=all`. Worth a toggle rather than a silent
change, since the two answer different questions.

**Fundamentals.** Market cap, P/E, 52-week range. The 52-week range in
particular would fit the existing stat rail and day-range bar directly, and can
be derived from stored daily bars without any new data source.

**Export.** CSV of trades, or a JSON snapshot of the whole portfolio. Small,
and it makes the paper portfolio useful outside the app.

## Deliberately not planned

**Real trading.** Wiring `POST /api/portfolio/orders` to Alpaca's order endpoint
is a handful of lines, and that is exactly the problem. Real orders need order
lifecycle handling (partial fills, rejects, cancels), reconciliation against
broker state, and a confirmation flow that makes accidental submission hard.
The simulated portfolio is the honest scope for a local tool.

**Authentication.** The app binds to localhost and has no users. Adding login
without a deployment story to justify it is complexity for its own sake. If
this is ever hosted, that decision changes first — see
[SECURITY.md](SECURITY.md).

**A front-end framework.** The client is ~1,500 lines of ES modules with no
build step, and it loads instantly. A framework would add a toolchain, a
`node_modules`, and a rebuild between every edit, in exchange for conveniences
this size of app does not need.

**Persisting intraday bars.** Millions of rows a month for data nothing reads
after the chart is painted. See [ARCHITECTURE.md](ARCHITECTURE.md).
