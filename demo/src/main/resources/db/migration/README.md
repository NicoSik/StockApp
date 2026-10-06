# Migrations

The schema, as numbered SQL files. The app applies any that have not run yet on
startup, each in its own transaction, and records them in `schema_migration`.

## What's inside

| File | Adds |
|---|---|
| `V001__baseline` | `stock`, `stock_price` |
| `V002__stock_price_ohlcv` | real OHLCV columns on `stock_price`, and the upsert index |
| `V003__watchlists` | `watchlist`, `watchlist_item` |
| `V004__paper_portfolio` | `portfolio`, `position`, `trade` |
| `V005__price_alerts` | `price_alert` |
| `V006__search_indexes` | indexes for symbol and company search |
| `V007__aggregator` | `account`, `snapshot`, `holding`, `instrument`, `instrument_alias`, `fx_rate` |
| `V008__linked_accounts` | API-linked accounts and `instrument.external_id`, for eToro |
| `V009__simulated_accounts` | `account.simulated` |
| `V010__snapshot_cost_basis` | a broker's portfolio-level cost basis on `snapshot` |

## Adding one

1. Create the next file: `V0NN__what_it_does.sql`.
2. Append its filename to `MIGRATIONS` in `stockapp/Db.java`. The list is
   explicit because a shaded jar cannot list a classpath directory.
3. Start the app; the log says `Applied migration V0NN__…`.

## Rules

- **Never edit a migration that has shipped.** It has already run on every
  existing database; write a new one instead.
- Write migrations so they can be re-read safely — `IF NOT EXISTS`, explicit
  constraint names.
- One migration, one purpose. Name it for what it does.
