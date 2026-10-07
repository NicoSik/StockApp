# Architecture

This document explains how the pieces fit and *why* they are built this way.
What each folder contains, and the rules for working in it, live in a
`README.md` in that folder — start at
[`demo/src/main/java/stockapp/README.md`](../demo/src/main/java/stockapp/README.md).

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
  web               │  web/Api          routes             │
                    │  web/ErrorHandlers status codes      │
                    │  web/Json         validation         │
                    │  web/GsonMapper   Javalin ⇄ Gson     │
                    └──────────────────────────────────────┘
                                    │
                    ┌──────────────────────────────────────┐
  services          │  MarketData      quotes, candles     │
                    │  PortfolioService valuation, history │
                    │  AlertService     threshold checks   │
                    │  AlpacaSync       assets, daily bars │
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

That is the paper-trading half. The holdings half has the same layers with
different upstreams — see [The aggregator](#the-aggregator).

`App.java` builds the whole graph explicitly in `main` — no dependency
injection container. With a dozen objects, a constructor call you can read top
to bottom is clearer than annotations, and startup order is exactly the order
things must happen: credentials, then database, then network.

One Javalin 7 detail that surprises people coming from 6: routing lives on the
config object, not on the `Javalin` instance. There is no `app.get(...)`.
Handlers and exception mappings are registered against `config.routes` inside
the `Javalin.create` lambda, which is why `Api` is constructed before the server
and takes a `RoutesConfig` rather than a `Javalin`.

## Conventions

- **One HTTP client.** `App` builds a single `OkHttpClient`; each upstream
  client (`alpaca/`, `etoro/`, `yahoo/`, `norgesbank/`) derives its own
  timeouts from it with `newBuilder()`, so they share one connection pool and
  one dispatcher, and shutdown releases them once.
- **One error contract.** Handlers throw `BadRequest`, `NotFound` or a domain
  exception; `web/ErrorHandlers` is the only place that maps them to status
  codes. See [API.md](API.md).
- **Logging** goes through SLF4J to the console and `logs/ticker.log`, rotated
  daily. Unexpected request errors are logged with their stack trace and never
  returned to the browser.
- **Loopback only.** The server binds to `127.0.0.1`. Jetty's default is every
  interface, which for an unauthenticated app holding real account data would
  mean anyone on the same network could read it.

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
| Daily closes and rates for holdings | PostgreSQL | The value history is rebuilt from them without waiting on the network |

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
importer/     broker files -> rows
market/       rows -> verified symbols
yahoo/        prices: Oslo, Stockholm, US, funds
norgesbank/   NOK exchange rates
etoro/        live eToro positions
service/      import, eToro sync, valuation
repo/         accounts, snapshots, instruments, rates
web/          /api/holdings/*
```

### Why Alpaca isn't used here

Alpaca is US equities only, and matching a Norwegian portfolio against it is
actively dangerous rather than merely incomplete: it resolves `DNB` to Dun &
Bradstreet, has no entry for Norsk Hydro, and returns Equinor's NYSE ADR rather
than the Oslo listing. Yahoo has the real Oslo and Stockholm listings in their
own currencies. Alpaca still powers the watchlist and paper portfolio.

### Identity, usually without an ISIN

An ISIN names an instrument exactly, and the resolver tries it first when a row
has one: `DNBBeholdning.xlsx` carries one on every row, and a hand-entered fund
may. But Nordnet's export has only a name and DNB's main report only a ticker,
so for most rows identity is inferred — and inference needs a check. Three
things make it safe:

1. **Currency pins the exchange.** A NOK holding is on Oslo Børs, SEK is
   Stockholm. That eliminates most wrong candidates before a price is fetched.
2. **Derivatives are filtered out.** Yahoo's search returns options contracts,
   and an option often trades near its underlying — so it can pass a price
   check. Filtering on instrument type and OCC symbol shape is what makes the
   price check trustworthy rather than merely usually right.
3. **The export's own price is the proof.** If the resolved symbol's live price
   disagrees with the broker's, the match is refused and handed to a human.

**Funds are the hard case.** Neither broker's export lists them, so they are
entered by hand into their own accounts ("DNB Fond", "Nordnet Fond") — kept
apart because an import replaces its account's whole snapshot, and a fund filed
under "DNB" would vanish at the next DNB import. A search for "DNB Global
Indeks" returns six share classes across two domiciles, scoring within a point
of each other. Units and value settle it: their quotient is the NAV, the classes
are nowhere near each other, and the same price check picks the right one. An
ISIN, when the user has one, is exact and skips the search.

Only a settled mapping is remembered as an alias. It is stored under the
row's strongest label — ISIN, else ticker, else name — and looked up under all
three, so a match remembered by name before a file carried an ISIN is still
found once it does. Caching an unverified guess
would skip the price check on every future import — which is how a wrong match
becomes permanent and invisible.

### eToro: linked, not imported

eToro is the only one of the three brokers with a personal API, so its holdings
arrive live. Two static keys (`x-api-key`, `x-user-key`, plus a fresh
`x-request-id` per request), no OAuth exchange, no refresh.

The design decision that matters is that **eToro holdings are never re-priced.**
`liquidationValueAccountCurrency` — what eToro says a position is currently
worth — is taken as authoritative, and the only work left is USD→NOK.

That is not laziness, it is the only correct option. An eToro account can hold
plain shares, leveraged CFDs, short positions and copy portfolios side by side.
Only the first is "N shares of X" that a share price could value; a 5× short or
a copy portfolio is not. eToro already knows what each is worth. Resolving them
to tickers and multiplying by a price would produce numbers that look right and
are not.

Leverage and direction are stored per holding and surfaced in the UI, because a
table that renders a leveraged short identically to a shareholding is quietly
lying about the risk.

Three things about eToro's API that cost time and are not in its documentation:

- **It hangs under HTTP/2.** The same request answers in 0.2s over HTTP/1.1 and
  never responds over HTTP/2. OkHttp prefers HTTP/2 via ALPN, so the client
  pins `Protocol.HTTP_1_1` — without it every call stalls for the full read
  timeout and looks like a credentials problem.
- **Instrument metadata lives at `/market-data/instruments?instrumentIds=`**,
  and spells the key `instrumentID` where the portfolio endpoint spells the same
  thing `instrumentId`. `instrumentTypeID` is a number, not a description.
- **`x-request-id` must be a real GUID.** A malformed one is rejected with
  `RequestIdNotValidGuid` rather than ignored.

### Simulated accounts are shown, never counted

An eToro demo account reports a portfolio exactly like a real one, practice cash
included. Synced without a flag it lands in the combined total — the first demo
sync here turned 410k NOK into 1.4M.

`account.simulated` excludes such an account from every aggregate: the total,
the live/as-of split, the cost basis and the account count. It stays visible,
labelled, with its value reported separately as `simulatedNok`. This is the same
rule the paper portfolio follows, and the flag lives on the account rather than
being derived from configuration, because `ETORO_DEMO` can be flipped later
while a snapshot taken under it stays in the database forever.

### Snapshots, not mutations

An import writes a whole dated snapshot. Re-importing replaces that date
cleanly, and an undo is a delete. Neither broker exports transactions this app
could rebuild history from: DNB's "Mine ordre" is twelve months of orders and
cannot describe current positions, so it is detected and rejected.

### A value history from closes, not from imports

The history chart is rebuilt, not stored, the same way the paper portfolio's
curve is rebuilt from its trade log. For each trading day since the first
import, every real account's snapshot in force that day is valued at that day's
close and Norges Bank rate, through the same `Valuation.value` the live total
uses — so today's point equals the headline figure. A missing close or rate
carries the last one forward; anything without a price keeps its reported
value.

The closes (`instrument_close`) and rates (`fx_rate`) are stored, so the chart
reads only the database. `HoldingsHistorySync` fetches what is missing in the
background: at startup, after every import or eToro sync, and once a day. Each
run also refetches the last few stored days, because a close taken while a
session was open was a live price.

What it cannot see is a trade made between imports: until the next import or
sync, the curve values the holdings the last import described. For eToro the
scheduler syncs once a day while the app runs, which keeps that window to a
day; file imports stay as fresh as the last file.

### Two valuation paths, reported separately

An instrument with a verified symbol is priced live and converted at the Norges
Bank rate. Everything else keeps the value its broker reported, with the date
attached. The split is surfaced in the total rather than blurred, because
presenting an old broker figure as current is a small lie that compounds.

The sums live in `Valuation`, which does no I/O and is unit-tested.
`ValuationService` feeds it whatever prices are cached, however old, and
refreshes anything past a minute in the background — so the holdings page
never waits on Yahoo.

Norges Bank quotes SEK and DKK **per hundred**, flagged by a `UNIT_MULT`
column. Rates are normalised to "1 unit = n NOK" on the way in; taking
`OBS_VALUE` at face value values a Swedish holding at a hundred times its worth.

## Front end

No framework and no build step. The whole client is ES modules served straight
from the classpath.

The modules and the rules for working in them are in
[`public/js/README.md`](../demo/src/main/resources/public/js/README.md).

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
