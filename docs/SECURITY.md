# Security

## Open item: credentials in git history

**Status: needs action from you. Nothing in the working tree leaks any more,
but the history still does, and only you can rotate the keys.**

### What is exposed

This repository is public (`github.com/NicoSik/StockApp`). Three secrets were
committed at various points and remain readable in the history of that public
repository:

| Secret | Where | Commits |
|---|---|---|
| PostgreSQL password | `.vscode/settings.json`, `main.java`, and a committed `main.class` | 8 commits |
| Alpaca `API_KEY_ID` | `main.java` | 3 commits |
| Alpaca `API_SECRET_KEY` | `main.java` | 3 commits |

A commit titled *"Removed senetive information"* removed them going forward,
which is the usual and entirely reasonable instinct — but a git commit only
adds. The earlier objects are still in the repository and are still fetchable
by anyone who clones it, and by anyone who already did.

The Alpaca keys are paper-trading keys, so no real money is reachable with
them. They do still grant access to the paper account and to the market data
subscription attached to it.

### What has been fixed

- `.vscode/settings.json` no longer contains the database password. The app
  reads credentials from `.env` only, via `stockapp.Config`.
- `demo/target/` is no longer tracked. It contained a compiled `main.class`
  with the password embedded as a string constant, which greps and secret
  scanners generally miss.
- `.gitignore` was rewritten. The old one had trailing comments on pattern
  lines (`Thumbs.db   # Windows`), which git treats as part of the pattern, so
  those two rules matched nothing. It now also ignores `*.class` and `*.jar`
  and keeps `.env.example` explicitly un-ignored.

None of this touches history. It only stops the bleeding.

### What you should do

**1. Rotate the Alpaca keys.** This is the important one, and it takes a minute:

- Go to <https://app.alpaca.markets/paper/dashboard/overview>
- Regenerate the API key pair
- Put the new values in `.env`

The old pair stops working the moment you regenerate, which makes anything
already scraped from the history useless.

**2. The PostgreSQL password — lower priority than it first appears.**

Worth being accurate rather than alarmist. `postgresql.conf` has
`listen_addresses = '*'`, so the socket does accept connections on every
interface. But `pg_hba.conf` permits only:

```
host  all  all  127.0.0.1/32  scram-sha-256
host  all  all  ::1/128       scram-sha-256
```

Connections from any other address are rejected at authentication regardless of
whether the password is correct. **Knowing the password does not grant access to
this database from another machine.**

So the leaked password is not, by itself, a route into your data. Two things
still make it worth changing:

- **Reuse.** If that password is used on any other account, *that* account is
  what is exposed, and the fix belongs there rather than here. Bots scrape
  public repositories for exactly this.
- **Future access.** If `pg_hba.conf` is ever loosened for remote access, or
  this is deployed somewhere, the password is already public. Change it before
  that day rather than on it.

If either applies:

```sql
ALTER USER postgres WITH PASSWORD 'a new password';
```

Then update `DB_PASSWORD` in `.env`.

**3. Decide about the history itself.**

Rotating makes the leaked values worthless, which is usually enough. If you
also want them gone from the repository, the history has to be rewritten and
force-pushed:

```bash
# review first - this rewrites every commit and changes every SHA
git filter-repo --invert-paths --path demo/target --path .vscode/settings.json
git push --force
```

This is destructive and irreversible, it breaks every existing clone, and
GitHub may retain unreferenced objects for a while regardless. Rotate first;
treat the rewrite as optional tidying, not as the fix.

## Ongoing practice

- `.env` is the only home for credentials, and it is gitignored.
- `.env.example` is the tracked template and must never hold a real value.
- `Config.summary()` prints the configuration at startup with the API key
  masked to its last four characters, so logs and screenshots stay safe.
- Build output is not tracked, so a compiled constant cannot leak.
- The app binds to localhost and has no authentication. That is appropriate for
  a single-user tool on your own machine and **not** appropriate to expose to a
  network — anyone who can reach the port can trade the paper portfolio and
  read the watchlists. If you ever host it, put authentication in front first.

## Application-level notes

- **SQL injection**: every query uses `PreparedStatement` with bound
  parameters. No SQL is built by string concatenation, including the ranked
  search, where the search term is bound six times rather than interpolated.
- **XSS**: the front end escapes all untrusted values (company names from
  Alpaca, search text from the user) through `escapeHtml` before they reach
  `innerHTML`. The old server-rendered pages interpolated the requested symbol
  straight into markup; that code is gone.
- **Order validation**: quantity and side are validated server-side, buying
  power and share count are checked inside the same transaction that writes the
  trade, and the portfolio row is locked with `SELECT ... FOR UPDATE` so two
  concurrent orders cannot both pass the same buying-power check.
- **Error responses**: unexpected exceptions log a stack trace server-side and
  return a generic message, so internal details do not reach the browser.
