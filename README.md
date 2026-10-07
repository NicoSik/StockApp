# Ticker

A stock watcher with a Robinhood-style interface: live watchlists, scrubable
price charts, and a paper portfolio that never touches a broker.

Java 17 · Javalin 7 · PostgreSQL · Alpaca, Yahoo, Norges Bank and eToro · a
vanilla-JS front end with no build step.

![The Ticker watchlist and stock detail view](docs/screenshots/ticker.png)

## What it does

- **Watchlists** — a rail of symbols with live prices, percent moves and a
  sparkline per row. The whole rail refreshes in a single API call.
- **Charts** — 1D / 1W / 1M / 3M / 1Y / 5Y. Drag across the chart (or use the
  arrow keys) and the price, change and timestamp in the header follow your
  cursor. The 1D baseline is the previous session's close, drawn as a dashed
  line, exactly like the app it borrows from.
- **Search** — `Ctrl-K` opens a command palette over all ~14,700 US equities,
  ranked so an exact ticker beats a prefix, which beats a company-name match.
- **Paper portfolio** — virtual cash, simulated buys and sells at the last
  trade price, positions with average cost and realised/unrealised P&L, and a
  portfolio value curve rebuilt from your trade log.
- **Price alerts** — one-shot above/below thresholds, evaluated every minute.
- **Themes** — dark and light, plus a colour-blind-safe palette that swaps
  green/red for blue/orange.

Everything is local. No order ever reaches a broker.

## Holdings — your real brokers, in one NOK total


![The Holdings view, combining several brokers into one NOK total](docs/screenshots/holdings.png)

*Both screenshots use invented holdings — the figures are not anyone's real
portfolio. Market prices in them are genuine.*

Separate from the paper portfolio, and deliberately so: mixing simulated money
into a real net-worth figure is not something to do by accident.

Drop in an export and it becomes one combined total:

| Broker | How | Identified by |
|---|---|---|
| **eToro** | **live API** — no export needed | eToro instrument id |
| **Nordnet** | *Aksjelister* (`.csv`) | name — no ISIN, no ticker |
| **DNB** | holdings report (`.xlsx`) | ticker, or ISIN |

DNB emits two different holdings workbooks and both are read: the Norwegian
one, with an `Aksjer` sheet keyed by ticker and a `Total` sheet to reconcile
against, and `DNBBeholdning.xlsx`, which has English headers and one sheet per
asset class. The second carries an ISIN — the one exact identifier any of these
files offers — so its rows are looked up by ISIN first, and by name only if
that finds nothing.

eToro is the only one of the three offering a personal API. Add
`ETORO_API_KEY` and `ETORO_USER_KEY` to `.env` (Settings → Trading → API Key
Management, Read permission) and a **Sync eToro** button appears. Its holdings
are valued by eToro rather than re-priced here — an eToro account can mix plain
shares with leveraged CFDs, shorts and copy portfolios, and only the first is
something a share price could value. Leverage and short positions are labelled
in the table rather than shown as though they were ordinary stock.

- **Live pricing where it exists.** Shares, ETFs and Norwegian mutual funds
  are priced from Yahoo in their own currency — Oslo Børs, Stockholm and US
  listings alike. Norges Bank supplies the NOK rates.
- **Funds are entered once, then priced live.** Neither export lists mutual
  funds, so you type each one in — name, units, value, and an ISIN if you have
  it. Units and value give the NAV, which is what picks the right share class
  out of the half-dozen a fund search returns.
- **Honest where it isn't.** Anything without a verified symbol carries the
  value your broker last reported, stamped with its date. The total says so:
  *"412 500 kr — 99.9% priced live, 600 kr as of 14 Aug"*.
- **Matches are verified, not guessed.** The exports rarely identify an
  instrument exactly — Nordnet's has only a name — so an instrument is resolved
  from a name or ticker and then checked against the price in your own file. A
  mismatch is refused and handed to you to fix rather than quietly believed —
  which is what caught a Nordnet line called "AEye A" resolving to AudioEye
  (`AEYE`) when the holding was AEye Inc (`LIDR`).
- **Imports are reversible.** Each one writes a dated snapshot rather than
  editing holdings, so re-importing is safe.
- **A value history for every day.** The chart values your latest import at
  each day's closing price and exchange rate, from your first import to today.
  Trades made between imports appear at the next import or eToro sync, and
  anything without a market price stays at the value your broker reported.

Broker exports live in `imports/`, which is gitignored.

## Requirements

| | |
|---|---|
| Java | **17 or newer** (Javalin 7 is compiled for 17) |
| Maven | not required — the repo ships a wrapper |
| PostgreSQL | 12+ |
| Alpaca account | free paper account works; a data subscription unlocks more |

## Setup

**1. Configure**

```bash
cp .env.example .env
```

Fill in your PostgreSQL password and your Alpaca keys from
<https://app.alpaca.markets/paper/dashboard/overview>.

**2. Create the database**

Only an empty database is needed. The app creates and migrates its own tables
on startup.

```bash
createdb -h localhost -p 5433 -U postgres postgres
```

**3. Run**

```bash
./run.sh          # macOS, Linux
.\run.bat         # Windows
```

The launcher checks your `.env`, finds a JDK 17+, verifies the port is free,
then starts the server. Open <http://localhost:9090>.

Other ways in:

```bash
cd demo && ./mvnw compile exec:java         # macOS, Linux, Git Bash, WSL
cd demo && .\mvnw.cmd compile exec:java     # Windows
./run.sh --package                          # build demo/target/ticker.jar
.\run.ps1 -Package                          # the same, on Windows
```

The first `mvnw` run downloads Apache Maven (~9 MB, SHA-512 verified) into
`~/.m2/wrapper`. After that it is offline and instant.

## How it fits together

```
browser ── /api/* JSON ──▶ Javalin ──▶ services ──┬──▶ PostgreSQL   what you own
   │                                              ├──▶ Alpaca       US quotes and bars
   └─ static files (no bundler)                   ├──▶ Yahoo        Oslo, Stockholm, funds
                                                  ├──▶ Norges Bank  NOK exchange rates
                                                  └──▶ eToro        live eToro positions
```

- **Quotes and intraday bars** are fetched live and cached in memory for
  15–60 seconds. They are not stored — they go stale in seconds and nothing
  needs them once the chart is drawn.
- **Daily bars** are written to `stock_price`. They power the portfolio value
  curve and act as the offline fallback when Alpaca is unreachable.
- **Holdings** are stored as a dated snapshot per account. Their live prices
  are cached for a minute and refreshed in the background, so the page never
  waits on Yahoo. Daily closes and exchange rates are stored too, and the value
  history is rebuilt from them.
- **Your data** — watchlists, trades, positions, alerts, holdings — lives only
  in your database.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full picture,
[docs/API.md](docs/API.md) for the endpoints, and
[docs/DESIGN.md](docs/DESIGN.md) for the interface rules.

## Database

The schema is applied automatically from versioned migrations in
`demo/src/main/resources/db/migration/`. Applied files are recorded in
`schema_migration`; each runs once, in its own transaction.

To change the schema, add the next `V0NN__name.sql` and append its filename to
the `MIGRATIONS` array in `Db.java`. Never edit a migration that has shipped.

## Tests

```bash
cd demo && ./mvnw test        # .\mvnw.cmd test on Windows
```

The tests cover the pure logic: holdings valuation, broker-file parsing,
instrument matching, Norges Bank rate parsing, range parsing and lookback
windows, quote arithmetic, sparkline downsampling, daily-job scheduling,
request validation and timestamp parsing. Anything needing a database or the
network is exercised by running the app, not by a mock.

Two tests guard the docs and the schema against drift: `ApiDocsTest` fails
when `docs/API.md` and the registered routes disagree, and `MigrationsTest`
fails when a migration file is missing from `Db.MIGRATIONS`.

GitHub Actions runs the suite on every push to `main` and every pull request,
on JDK 17 and 21 (`.github/workflows/ci.yml`).

`RealExportTest` also runs the parsers against whatever real exports are in
`imports/`, and skips itself when there are none.

## Configuration

Every value is read from JVM system properties, then environment variables,
then the nearest `.env` walking up from the working directory, then a default.
See `.env.example` for the full list. Useful ones:

| Variable | Default | Notes |
|---|---|---|
| `SERVER_PORT` | `9090` | |
| `DATA_FEED` | `sip` | Falls back to `iex` automatically on a 403 |
| `PAPER_STARTING_CASH` | `100000` | Applied only when the portfolio is created |
| `SYNC_ASSETS_ON_START` | `false` | The full asset refresh is tens of MB |
| `QUOTE_CACHE_SECONDS` | `15` | How long a quote is reused |

## Troubleshooting

**`Text Blocks are only available with source level 15 and above`**
A stale `target/` built by an older JDK. Run `cd demo && ./mvnw clean compile`.
If VS Code keeps recreating it, its Java extension is using an old runtime —
point `java.jdt.ls.java.home` in your VS Code *user* settings at a JDK 17+ and
reload the window.

**`Alpaca credentials are missing`**
No `.env`, or `API_KEY_ID` / `API_SECRET_KEY` are empty. The app deliberately
refuses to start rather than fail later on the first request.

**Prices show but charts are empty**
Your account is not entitled to the requested feed. The client detects this and
downgrades to `iex` for the rest of the run; the startup log shows the feed in
use. Set `DATA_FEED=iex` to skip the probe.

**`Connection refused` on startup**
PostgreSQL is not running, or `DB_URL` points at the wrong port. Note the
default here is **5433**, not the usual 5432.

**Port already in use**
The launcher reports which process holds it. Change `SERVER_PORT` in `.env` or stop
that process.

## Security

`.env` is gitignored and is the only place credentials belong. Build output and
broker exports are not tracked. The app listens on 127.0.0.1 only and has no
authentication, which suits a single-user tool and rules out hosting it as-is.

See [docs/SECURITY.md](docs/SECURITY.md).

## Licence

For educational and personal use.
