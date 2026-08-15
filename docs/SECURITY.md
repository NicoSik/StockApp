# Security

How this app handles credentials and untrusted input.

## Credentials

- **`.env` is the only home for secrets**, and it is gitignored. Nothing else
  in the repository should ever contain a password, key or token.
- **`.env.example` is the tracked template** and holds placeholder values only.
- **Build output is not tracked.** Secrets compiled into `.class` files as
  string constants survive a source-level cleanup and are missed by most greps
  and many secret scanners, so `target/` stays out of git entirely.
- **Editor settings are not a config store.** `.vscode/settings.json` is
  tracked, so it holds no environment values; anything sensitive belongs in
  `.env`, which `stockapp.Config` reads.
- **Startup logging is safe to share.** `Config.summary()` prints the
  configuration with the API key masked to its last four characters, so a
  screenshot or a pasted log cannot leak one.

If a credential ever does reach a commit, **rotate it**. Removing it in a later
commit does not remove it from history — git only ever adds — and rotation is
what actually makes the exposed value worthless.

**Grant the narrowest permission that works.** The eToro key needs only *Read*;
this app never places an eToro order, so a key with Write permission would carry
risk it has no use for. eToro also supports IP allow-listing and an expiry date
on a key, both worth setting. Demo and Real are separate environments with
separate keys — a demo key cannot touch a real account, which makes it the safer
one to try first.

## Network exposure

The app binds to localhost and has **no authentication**. That is appropriate
for a single-user tool on your own machine and **not** appropriate to expose to
a network: anyone who can reach the port can trade the paper portfolio, read
your holdings and see your watchlists.

If you ever host it, add authentication first. That also changes the risk
calculus for anything reachable only because it is local today.

## Untrusted input

Broker exports are files from outside the app, and they are treated as such.

- **XML parsing is hardened.** The `.xlsx` reader disables DOCTYPE
  declarations, external entity resolution and XInclude, so a crafted workbook
  cannot read local files or make the parser issue network requests.
- **Decompression is bounded.** Zip entries are refused past a size cap, so a
  zip bomb cannot exhaust memory.
- **Uploads are capped** at 8 MB; genuine broker exports are a few kilobytes.
- **Imports are never blind.** A file is parsed into a preview showing how each
  row resolved and how confident that is. Nothing is written until it is
  committed.

Broker exports themselves are gitignored (`imports/`, plus the filename
patterns the banks produce). They contain real holdings, position sizes and
account numbers.

## Application

- **SQL injection**: every query uses `PreparedStatement` with bound
  parameters. No SQL is assembled by string concatenation, including the ranked
  search, where the term is bound six times rather than interpolated.
- **XSS**: all untrusted values — company names from third-party APIs, search
  text, broker export contents — pass through `escapeHtml` before reaching
  `innerHTML`.
- **Order validation**: quantity and side are validated server-side; buying
  power and share count are checked inside the same transaction that writes the
  trade, with the portfolio row locked `FOR UPDATE` so two concurrent orders
  cannot both pass the same check.
- **Error responses**: unexpected exceptions log a stack trace server-side and
  return a generic message, so internal details never reach the browser.

## Third-party data

Market data comes from Alpaca (US equities) and Yahoo (Oslo Børs, Stockholm).
**The Yahoo endpoint is unofficial** — not documented or supported, and it can
change without notice. That is an accepted trade for a local personal tool;
when it breaks, holdings fall back to the value their broker last reported.

Instrument matching never trusts a name alone. A resolved symbol is verified
against the price in the export before it is allowed to drive a valuation,
because a plausible-looking wrong match — a similarly named company, a foreign
share class, an options contract — is worse than no match at all.
