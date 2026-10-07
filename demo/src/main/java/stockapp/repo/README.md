# repo

All PostgreSQL access. One class per table group, each borrowing a connection
from the pool per query.

## What's inside

Paper trading:
- **`StockRepo`** — the US asset table and daily bars. `findBySymbol`, `findBySymbols` (many in one query), `findById`, `search(term, limit)` (ranked), `upsertAssets`, `saveDailyBars`, `dailyBars(stockId, from)`, `latestStoredCloses`, `count`.
- **`WatchlistRepo`** — `listAll`, `find`, `create`, `rename`, `delete`, `addItem`, `removeItem`, `reorder`, and `allSymbols` (everything worth pre-fetching).
- **`PortfolioRepo`** — `executeTrade(…)` locks, checks and writes a fill, or throws `TradeRejected`. Also `positions`, `trades`, `tradeLots` (every fill, for rebuilding history), `totalRealizedPnl`, `ensurePortfolio`, `load`, `reset`.
- **`AlertRepo`** — `create`, `listAll`, `find`, `delete`, `pending()` (unfired, with symbol), `markTriggered(id, price)` (fires once).

Holdings:
- **`AccountRepo`** — `ensureAccount`, `listAccounts`, `findAccount`, `writeSnapshot(…)` (replaces that date's snapshot in one transaction), `snapshots(accountId)` (oldest first), `latestSnapshot`, `holdings(snapshotId)`.
- **`InstrumentRepo`** — `upsert`, `upsertExternal` (keyed by a broker's own id, e.g. eToro), `findById`, `findBySymbol`, `findByAlias`, `linkAlias`, `listAll`.
- **`FxRepo`** — `save(date, rates)`, and `latest()` for when Norges Bank is down. `saveHistory` and `history(from)` store and read dated rates for the value history.
- **`BankLinkRepo`** — bank consents through Enable Banking and their accounts: `save(bank, country, session)` (replaces that bank's earlier link), `all()`.
- **`ClosingPriceRepo`** — daily closes for priced holdings, keyed by symbol: `save(symbol, closes)`, `storedRange(symbol)`, `closes(symbols, from)`.

## Not here

- Business rules that are not about storage. The exception is the paper-trade
  check, which must happen inside the transaction that writes the trade.

## Rules

- **Every query is a `PreparedStatement`** with bound parameters. No SQL is
  built from strings, including search.
- **Holdings are snapshots.** An import writes a whole dated snapshot of an
  account; it never edits holdings in place. Re-importing a date replaces it.
- **Trades lock first.** `PortfolioRepo` takes `SELECT … FOR UPDATE` on the
  portfolio and position rows before checking buying power, so two concurrent
  orders cannot both pass.
- Schema changes are new migrations in `resources/db/migration/`, never edits
  here alone.
