# Architecture

## Shape

```
                    ┌──────────────────────────────────────┐
  browser           │  resources/public/                   │
  ────────          │    index.html                        │
                    │    css/  tokens.css, app.css         │
                    │    js/   app, chart, api, palette…   │
                    └──────────────────────────────────────┘
                                    │ fetch /api/*
                                    ▼
                    ┌──────────────────────────────────────┐
  web               │  web/Api          routes + errors    │
                    │  web/Json         validation         │
                    │  web/GsonMapper   Javalin ⇄ Gson     │
                    └──────────────────────────────────────┘
                                    │
                    ┌──────────────────────────────────────┐
  services          │  MarketData      quotes, candles     │
                    │  PortfolioService valuation, history │
                    │  AlertService     threshold checks   │
                    │  Importer         assets, daily bars │
                    │  Scheduler        background jobs    │
                    │  Cache            TTL map            │
                    └──────────────────────────────────────┘
                          │                        │
        ┌─────────────────┘                        └──────────────┐
        ▼                                                         ▼
┌──────────────────────┐                        ┌──────────────────────────┐
│ repo/                │                        │ alpaca/AlpacaClient      │
│   StockRepo          │                        │   /v2/clock              │
│   WatchlistRepo      │                        │   /v2/assets             │
│   PortfolioRepo      │                        │   /v2/stocks/snapshots   │
│   AlertRepo          │                        │   /v2/stocks/bars        │
└──────────────────────┘                        └──────────────────────────┘
        │                                                         │
        ▼                                                         ▼
   PostgreSQL (HikariCP)                                    Alpaca Markets
```

`App.java` builds the whole graph explicitly in `main` — no dependency
injection container. With a dozen objects, a constructor call you can read top
to bottom is clearer than annotations, and startup order is exactly the order
things must happen: credentials, then database, then network.

One Javalin 7 detail that surprises people coming from 6: routing lives on the
config object, not on the `Javalin` instance. There is no `app.get(...)`.
Handlers and exception mappings are registered against `config.routes` inside
the `Javalin.create` lambda, which is why `Api` is constructed before the server
and takes a `RoutesConfig` rather than a `Javalin`.

## What is stored and what is not

This is the decision most of the design hangs off.

| Data | Where | Why |
|---|---|---|
| Asset universe (~14.7k) | PostgreSQL | Changes rarely; search must be instant |
| Daily bars | PostgreSQL | Powers portfolio history; offline fallback |
| Intraday bars (5Min/30Min/1Hour) | Memory, 30 s–5 min TTL | Large, stale in seconds, needed only to draw |
| Quotes | Memory, 15 s TTL | Same |
| Sparklines | Memory, 30 s TTL | Derived from a single batched bars call |
| Watchlists, trades, positions, alerts | PostgreSQL | Yours; must survive a restart |

Persisting intraday bars was considered and rejected. It would add millions of
rows a month for data that nothing reads after the chart is painted, and the
API serves it in one round trip anyway.

## Request paths worth knowing

**`GET /api/rows?symbols=A,B,C`** — the watchlist rail.

This is the endpoint the design is built around. A rail of 30 symbols needs 30
prices and 30 sparklines, and the naive version is 60 upstream calls. Instead:

1. `MarketData.quotes` checks the cache per symbol and requests only the misses,
   batched 100 at a time into Alpaca's multi-symbol snapshot endpoint.
2. `MarketData.sparklines` does the same against the multi-symbol *bars*
   endpoint, then downsamples each series to 48 points.

A rail polling every 15 seconds costs at most two upstream calls per tick, and
usually zero, because the caches are still warm.

**`GET /api/stocks/{symbol}/candles?range=1D`** — the chart.

`Range` maps the UI label onto an Alpaca timeframe and a lookback window
(`Range.java`). Two details matter:

- The lookback is deliberately wider than the label — 8 days for "1D", 10 for
  "1W" — because markets close. `LocalDate.minus(Period)` does the subtraction,
  not a `Duration` in days; converting a `Period` to days double-counts years
  and made 5Y request ten years of bars.
- For 1D the window is anchored to **the most recent session that saw
  regular-hours trading**, and everything from that session onward is kept.
  Anchoring to the newest bar's calendar date instead collapses the chart to
  two points every morning between 04:00 and 09:30 ET, when the only bars
  carrying today's date are the first few pre-market prints.

The previous close used as the chart baseline is computed from the same bar set
the line is drawn from — the last regular-hours close before the session — so
the number in the header can never disagree with the line beneath it.

**`POST /api/portfolio/orders`** — a simulated fill.

`PortfolioRepo.executeTrade` does everything in one transaction:

1. `SELECT cash ... FOR UPDATE` locks the portfolio row.
2. `SELECT ... FOR UPDATE` locks the position row.
3. Buying power (buy) or share count (sell) is checked.
4. Cash, position and trade are all written, or none are.

The row lock is what makes the check meaningful: without it two concurrent
orders could both read the same balance and both pass.

Average cost is a weighted average of the existing basis and the new lot.
Selling leaves average cost untouched and books
`(fill − avgCost) × quantity` into `realized_pnl`. A closed position keeps its
row at quantity 0 so realised P&L survives.

**`GET /api/portfolio/history`** — the value curve.

Rebuilt from the trade log rather than stored: replay every fill in order to
get cash and share counts on each day, then value the shares at that day's
close, forward-filling through holidays. No snapshot table means nothing to
drift out of sync, and a corrected trade corrects the whole curve for free.

## Failure behaviour

The app degrades rather than erroring:

| Failure | Behaviour |
|---|---|
| Alpaca unreachable, quotes | Last cached value is served; UI keeps rendering |
| Alpaca unreachable, charts | Falls back to stored daily bars, flagged `source: "database"` |
| Account not entitled to SIP | Detects the 403 once and downgrades to `iex` for the run |
| A position cannot be priced | Valued at cost, and the summary sets `stale: true` |
| A scheduled job throws | Caught and logged; the schedule survives |
| pg_trgm unavailable | Migration logs a notice; search falls back to a scan |

The last two are the ones that bite quietly. A `ScheduledExecutorService` task
that throws is cancelled for the lifetime of the process with no message at
all, which is why every job body is wrapped.

## Notable fixes from the rewrite

| Was | Now |
|---|---|
| One `java.sql.Connection` shared across Javalin's thread pool | HikariCP; a connection per query. `Connection` is not thread-safe, and concurrent requests could interleave on it |
| `maven.compiler.source 1.8` against Javalin (Java 17 bytecode) | `release 17`. The old pom could not compile — this is why the server would not start |
| logback 1.2.x with Javalin's SLF4J 2.x API | logback 1.5.x. 1.2 implements only the SLF4J 1.7 SPI and binds silently to a no-op logger |
| HTML built by string concatenation in `main.java`, symbols interpolated into markup | JSON API + escaped client rendering |
| `stock_price` with no price columns; `getPriceHistory` returning nulls | Real OHLCV with a `(stock_id, date)` unique index for upserts |
| `PopulateDB` inserting `market` into a schema that lacked the column | `V001` backfills it |
| `UpdateDB` with `SELECT your_column FROM ?` (table names cannot be bound) | Deleted; it was dead code |
| `Scheduler.schedulePriceUpdate` dereferencing a null field | Rewritten with DST-safe daily rescheduling |

## The aggregator

A second, separate world: real holdings imported from brokers, valued in NOK.
It shares nothing with the simulated portfolio — separate tables, separate
endpoints, separate screen — which is what made it additive rather than a
rewrite.

```
importer/     NordnetParser, DnbParser, XlsxReader
market/       YahooClient, InstrumentResolver
service/      ImportService, ValuationService, FxService
repo/         AccountRepo, InstrumentRepo, FxRepo
web/          AggregatorApi   (/api/holdings/*)
```

### Why Alpaca isn't used here

Alpaca is US equities only, and matching a Norwegian portfolio against it is
actively dangerous rather than merely incomplete: it resolves `DNB` to Dun &
Bradstreet, has no entry for Norsk Hydro, and returns Equinor's NYSE ADR rather
than the Oslo listing. Yahoo has the real Oslo and Stockholm listings in their
own currencies. Alpaca still powers the watchlist and paper portfolio.

### Identity without an ISIN

Neither export carries one, so identity is inferred — and inference needs a
check. Three things make it safe:

1. **Currency pins the exchange.** A NOK holding is on Oslo Børs, SEK is
   Stockholm. That eliminates most wrong candidates before a price is fetched.
2. **Derivatives are filtered out.** Yahoo's search returns options contracts,
   and an option often trades near its underlying — so it can pass a price
   check. Filtering on instrument type and OCC symbol shape is what makes the
   price check trustworthy rather than merely usually right.
3. **The export's own price is the proof.** If the resolved symbol's live price
   disagrees with the broker's, the match is refused and handed to a human.

Only a settled mapping is remembered as an alias. Caching an unverified guess
would skip the price check on every future import — which is how a wrong match
becomes permanent and invisible.

### Snapshots, not mutations

An import writes a whole dated snapshot. Re-importing replaces that date
cleanly, an undo is a delete, and a value history accumulates without needing
transaction data — which matters, because neither broker exports transactions
this app could rebuild history from. DNB's "Mine ordre" is twelve months of
orders and cannot describe current positions, so it is detected and rejected.

### Two valuation paths, reported separately

An instrument with a verified symbol is priced live and converted at the Norges
Bank rate. Everything else keeps the value its broker reported, with the date
attached. The split is surfaced in the total rather than blurred, because
presenting a three-week-old fund NAV as current is a small lie that compounds.

Norges Bank quotes SEK and DKK **per hundred**, flagged by a `UNIT_MULT`
column. Rates are normalised to "1 unit = n NOK" on the way in; taking
`OBS_VALUE` at face value values a Swedish holding at a hundred times its worth.

## Front end

No framework and no build step. The whole client is ES modules served straight
from the classpath.

| Module | Role |
|---|---|
| `app.js` | State, routing, both views, polling |
| `chart.js` | Canvas renderer, scrub interaction, trend colour |
| `sparkline.js` | The 68×30 variant — no animation, no observers |
| `api.js` | Every `fetch`; one error shape |
| `palette.js` | `Ctrl-K` search with debounce and abort |
| `format.js` | `Intl` formatters, built once at load |
| `dom.js` | `escapeHtml` and small helpers |
| `toast.js` | Notifications, in an aria-live region |

Routing uses the History API against real paths (`/AAPL`, `/portfolio`), with
Javalin's `spaRoot` serving `index.html` for unmatched paths. A catch-all
`GET /api/*` is registered last so an unknown API path returns a JSON 404
rather than HTML the client would try to parse.

`chart.js` publishes the trend colour to `--trend` on the document root, and
the range pills read it. One writer means the control and the line cannot
disagree.

Two subtleties in the chart worth preserving:

- The x axis is spaced by **index**, not timestamp. Real timestamps render the
  overnight gap as a long flat stretch and squash the session that matters.
- The reveal animation has a `setTimeout` backstop. `requestAnimationFrame`
  does not fire while a document is not being composited — a background tab, a
  hidden panel — and since all drawing lives in the rAF callback, without the
  backstop the canvas stays permanently blank in those states.
