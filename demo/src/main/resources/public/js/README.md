# Front end

ES modules served straight from the classpath — no framework, no bundler, no
build step. Edit a file, reload the page.

| Module | Role |
|---|---|
| `app.js` | Shell: theme, routing, the watchlist rail, polling, shortcuts |
| `state.js` | State shared by the shell and the views |
| `detail.js` | Stock view: header, chart, session stats, trade card, alerts |
| `portfolio.js` | Paper portfolio: value curve, positions, trade log |
| `holdings.js` | Real holdings: totals, accounts, investments and cash, imports, fund entry, eToro sync, bank linking |
| `chart.js` | Canvas renderer, scrub interaction, trend colour |
| `sparkline.js` | The small rail chart — no animation, no observers |
| `api.js` | Every `fetch`, and the one error shape |
| `palette.js` | `Ctrl-K` search with debounce and abort |
| `format.js` | `Intl` formatters, built once at load |
| `dom.js` | `escapeHtml`, the error panel and small helpers |
| `toast.js` | Notifications, in an `aria-live` region |

## Exports

- **`app.js`** — nothing; it starts the app on load.
- **`detail.js`** — `renderDetailView(main, symbol, hooks)`, `selectRange(symbol, range)`, `applyLiveQuote(quote)`.
- **`portfolio.js`** — `renderPortfolioView(main, { navigate })`.
- **`holdings.js`** — `renderHoldingsView(main)`, `teardownHoldingsChart()`.
- **`state.js`** — `state`, `RANGES`, `safeStore(key, value)`.
- **`chart.js`** — `Chart`: `setData({ points, baseline, range })`, `destroy()`; scrubbing reports through `onScrub`.
- **`sparkline.js`** — `drawSparkline(canvas, points, baseline)`.
- **`api.js`** — `api`, one method per endpoint (`rows`, `candles`, `order`, `holdings`, `previewImport`, …), and `ApiError`.
- **`palette.js`** — `SearchPalette`: `open()`, `close()`, `toggle()`.
- **`format.js`** — `usd`, `usdCompact`, `signedUsd`, `price`, `percent`, `signedPercent`, `shares`, `abbreviate`, `dateTime`, `axisLabel`, `tooltipLabel`, `direction`, `arrow`, and `EMPTY` for a missing value.
- **`dom.js`** — `escapeHtml`, `qs`, `qsa`, `setHtml`, `setText`, `setDirectionClass`, `errorPanel`.
- **`toast.js`** — `toast(message, tone)`.

## Rules

- **One module per screen.** `app.js` routes and polls; it does not render a
  view. A new screen is a new module exporting a `render…View(main, …)`.
- **Every request goes through `api.js`.** No `fetch` anywhere else.
- **Everything untrusted is escaped.** Company names, search text and broker
  file contents pass through `escapeHtml` before reaching `innerHTML`.
- **No third-party scripts or fonts.** The page must work offline.
- **Colour means direction only**, and never alone — every change also shows
  an arrow and a sign. The full interface rules are in `docs/DESIGN.md`.
- **Poll only while visible.** Timers stop on `visibilitychange`.
