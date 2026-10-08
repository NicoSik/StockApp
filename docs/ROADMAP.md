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

**More brokers.** The importer is a `BrokerParser` interface plus a per-broker
adapter; adding one is a parser and a format-detection check. Saxo is the
obvious candidate, since unlike the Norwegian retail brokers it has a real
OpenAPI and could skip file import entirely. Crypto exchanges (Firi, Coinbase,
Kraken) likewise.

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

**Tell deposits from returns in the value history.** The chart cannot tell a
market gain from money moved in: 50 000 kr transferred to Nordnet makes the
line jump as though it were earned. Bank transactions through Enable Banking
could fix that, narrowly: recognise the transfers between a linked bank and a
broker, record them as contributions, and let the chart show "you added 50 000,
the market added 3 000". Two things limit it. Banks describe a transfer each in
their own way, so matching them needs rules per bank. And unattended access
only reaches about 90 days back, so contributions are only known from when
syncing began. Where both sides are tracked, a transfer already nets to zero
in the combined total, so this matters most for an investments-only view.

Deliberately not a transaction list. Spending, categories and budgets are a
different app, and the full payment history is the most sensitive data a bank
holds, served here without login.

**Live sync for Nordnet and DNB, when they allow it.** Both still arrive as
exported files, so their holdings are only as fresh as the last import. eToro
shows what a live sync gives: a daily snapshot without anyone exporting
anything. As checked in October 2026, neither is possible yet:

- *Nordnet* has an External API (v2) with an endpoint for an account's
  positions, `GET /accounts/{accid}/positions`. Its own documentation says it
  is "currently not onboarding new customers". A Nordnet key also grants full
  trading access, with no read-only mode, so it would need more care than the
  read-only eToro key. If onboarding reopens: a `nordnet/` client with the
  Ed25519 login, a sync service shaped like `EtoroSyncService`, and the daily
  holdings job calling it. The CSV import stays as the fallback.
- *DNB* offers no personal API for share or fund holdings. Its developer
  portal has Open Banking (PSD2) APIs, which cover payment accounts, not
  custody accounts, and are for licensed third parties. The file import stays.

*Why this is rare.* No law requires it: PSD2 opened payment accounts to
licensed third parties and left share and fund accounts out. A brokerage key
can move money, the bank carries the fraud liability, and its login (BankID)
is built for a person, not a script. An API also earns the bank nothing and
lets customers leave its app.

*What could change it.* The EU's Financial Data Access regulation (FiDA)
covers securities and investment accounts. It was not adopted as of December
2025; adoption was expected around mid-2026, with obligations phased in from
about 2027 and reaching Norway later through the EEA. Even then, access is
likely to go through licensed providers rather than a personal key.

*Brokers with an API for your own holdings today*, in case any of the money
ever moves:

| Provider | Holdings API | Read-only key | Notes |
|---|---|---|---|
| eToro | Yes | Yes | Already integrated |
| Saxo | OpenAPI: positions, account | Via OAuth | Retail clients, own account; 24-hour token for testing |
| Interactive Brokers | Web API: positions | Login-based | Runs through a local gateway you log in to in the browser |
| Trading 212 | Public API (beta): positions | No scopes documented | Invest and Stocks ISA accounts only |
| Nordnet | `/accounts/{accid}/positions` | No | Not onboarding new customers |
| Firi (crypto) | Developer site exists | Not confirmed | Details not checked |
| DNB, Nordea, other banks | No | — | Open Banking covers payment accounts only |

Saxo is the natural first one: see *More brokers* above.

*The shape a bank integration should take.* Not a static key, but BankID
consent: "Connect DNB" opens the bank's login, BankID approves a read-only
grant, and the app gets a token limited to reading holdings that expires
(say after 180 days) and can be revoked in the bank. The daily holdings job
would then sync DNB and Nordnet like eToro, with a BankID re-approval about
twice a year. BankID cannot be automated and only the bank can offer the
login, so this needs the bank's co-operation - it is the model Open Banking
already uses for payment accounts, and what FiDA would require for
investments.

*What can be connected with BankID today.* Only bank accounts, through a
licensed Open Banking aggregator:

- *Enable Banking* covers DNB, SpareBank 1, Nordea, Handelsbanken and Danske
  Bank with a BankID redirect, and reportedly allows free access to your own
  linked accounts. Balances and transactions only - it would add cash in
  bank accounts to the total, not holdings.
- *Tink Investments* returns holdings with ISIN and quantity, but is sold to
  businesses and does not list Norway.
- *GoCardless Bank Account Data* (formerly Nordigen) stopped accepting new
  sign-ups in July 2025.

Worth checking again once a year, and when FiDA is adopted.
Sources: [Nordnet API – Getting started](https://www.nordnet.se/externalapi/docs/getting_started),
[Nordnet API documentation](https://www.nordnet.se/externalapi/docs/api),
[DNB Developer](https://developer.dnb.no/),
[CMS – key developments in 2026](https://cms.law/en/deu/publication/2026-themen-die-sie-bewegen-werden/digitalisation-of-the-financial-sector-in-transition-key-developments-in-2026),
[Saxo OpenAPI](https://www.developer.saxo/openapi/learn),
[Interactive Brokers Web API](https://www.interactivebrokers.com/campus/ibkr-api-page/cpapi-v1/),
[Trading 212 API](https://docs.trading212.com),
[Firi developers](https://developers.firi.com/),
[Enable Banking – Norway](https://enablebanking.com/docs/markets/no/),
[Tink Investments](https://tink.com/products/investments),
[GoCardless Bank Account Data alternatives](https://dev.to/johnfrandsen/gocardless-bank-account-data-alternatives-what-to-use-when-signups-are-disabled-326d).

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

**A front-end framework.** The client is ~3,200 lines of ES modules with no
build step, and it loads instantly. A framework would add a toolchain, a
`node_modules`, and a rebuild between every edit, in exchange for conveniences
this size of app does not need.

**Persisting intraday bars.** Millions of rows a month for data nothing reads
after the chart is painted. See [ARCHITECTURE.md](ARCHITECTURE.md).
