-- V001 - Baseline schema.
--
-- Written to be idempotent so it applies cleanly both to a brand new database
-- and to the existing one, which already contains `stock` (populated from the
-- Alpaca assets endpoint) and a stub `stock_price`.

CREATE TABLE IF NOT EXISTS stock (
    id      SERIAL PRIMARY KEY,
    symbol  TEXT NOT NULL UNIQUE,
    company TEXT NOT NULL,
    market  TEXT NOT NULL
);

-- The original Database.sql declared `market` but PopulateDB.java inserted it,
-- so a database created from that script alone would fail on insert. Backfill
-- the column for anyone in that state.
ALTER TABLE stock ADD COLUMN IF NOT EXISTS market TEXT;
UPDATE stock SET market = 'UNKNOWN' WHERE market IS NULL;
ALTER TABLE stock ALTER COLUMN market SET NOT NULL;

-- Metadata carried on the Alpaca asset record that is worth keeping.
ALTER TABLE stock ADD COLUMN IF NOT EXISTS tradable    BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE stock ADD COLUMN IF NOT EXISTS fractionable BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE stock ADD COLUMN IF NOT EXISTS asset_class TEXT;
ALTER TABLE stock ADD COLUMN IF NOT EXISTS updated_at  TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE TABLE IF NOT EXISTS stock_price (
    id       SERIAL PRIMARY KEY,
    stock_id INTEGER,
    date     DATE NOT NULL,
    volume   INTEGER NOT NULL DEFAULT 0
);
