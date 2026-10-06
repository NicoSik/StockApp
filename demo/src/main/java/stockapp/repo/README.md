# repo

All PostgreSQL access. One class per table group, each borrowing a connection
from the pool per query.

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
