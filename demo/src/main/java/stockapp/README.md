# stockapp

The backend. `App` builds the whole object graph by hand in `main` — no
dependency injection — in the order things must happen: configuration, then the
database, then the network. `Config` resolves every setting once at startup
(system property, environment, `.env`, default). `Db` owns the connection pool
and applies the migrations in `resources/db/migration/`.

Everything else lives in a package. The app has two halves that share no
tables, endpoints or screens:

- **Paper trading** — watchlists, charts, a simulated portfolio. US equities
  from Alpaca.
- **Holdings** — real accounts from DNB, Nordnet and eToro, valued in NOK.

| Package | What it is |
|---|---|
| `alpaca/`, `etoro/`, `yahoo/`, `norgesbank/` | One client per external API |
| `importer/` | Reading broker export files |
| `market/` | Deciding which instrument an export row refers to |
| `model/` | Plain data shared across layers |
| `repo/` | PostgreSQL access |
| `service/` | The logic, for both halves |
| `web/` | HTTP routes and the error contract |

## Rules

- New objects are wired in `App`, in dependency order. Nothing constructs its
  own collaborators.
- New settings go in `Config` with a default, and in `.env.example`.
- The server binds to `127.0.0.1`. It serves real holdings without
  authentication.
