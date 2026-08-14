-- V002 - Turn `stock_price` into a real daily OHLCV bar table.
--
-- The live table only had (id, stock_id, date, volume), which is why every
-- price in the UI rendered as "N/A": there was nowhere to put a price. It also
-- had no uniqueness on (stock_id, date), so re-running an import would have
-- duplicated every bar.

ALTER TABLE stock_price ADD COLUMN IF NOT EXISTS open_price  NUMERIC(14, 4);
ALTER TABLE stock_price ADD COLUMN IF NOT EXISTS high_price  NUMERIC(14, 4);
ALTER TABLE stock_price ADD COLUMN IF NOT EXISTS low_price   NUMERIC(14, 4);
ALTER TABLE stock_price ADD COLUMN IF NOT EXISTS close_price NUMERIC(14, 4);

-- `price` is the canonical price for the day (== close_price). Kept as its own
-- column because the original schema and the app both refer to it.
ALTER TABLE stock_price ADD COLUMN IF NOT EXISTS price       NUMERIC(14, 4);

-- Volume-weighted average price and trade count, both supplied by Alpaca.
ALTER TABLE stock_price ADD COLUMN IF NOT EXISTS vwap        NUMERIC(14, 4);
ALTER TABLE stock_price ADD COLUMN IF NOT EXISTS trade_count BIGINT;

-- Daily consolidated volume routinely exceeds INTEGER range on high-volume
-- tickers, which would throw at insert time.
ALTER TABLE stock_price ALTER COLUMN volume TYPE BIGINT;
ALTER TABLE stock_price ALTER COLUMN volume SET DEFAULT 0;

-- A bar with no parent stock is meaningless; drop orphans, then enforce it.
DELETE FROM stock_price WHERE stock_id IS NULL;
ALTER TABLE stock_price ALTER COLUMN stock_id SET NOT NULL;

-- Deleting a stock should take its bars with it.
ALTER TABLE stock_price DROP CONSTRAINT IF EXISTS stock_price_stock_id_fkey;
ALTER TABLE stock_price
    ADD CONSTRAINT stock_price_stock_id_fkey
    FOREIGN KEY (stock_id) REFERENCES stock (id) ON DELETE CASCADE;

-- Required for the ON CONFLICT upsert the importer uses, and it is also the
-- index that every chart query reads.
DELETE FROM stock_price a
      USING stock_price b
      WHERE a.id > b.id
        AND a.stock_id = b.stock_id
        AND a.date = b.date;

CREATE UNIQUE INDEX IF NOT EXISTS stock_price_stock_date_uidx
    ON stock_price (stock_id, date);

-- Chart and portfolio queries always read a symbol's bars newest-first.
CREATE INDEX IF NOT EXISTS stock_price_stock_date_desc_idx
    ON stock_price (stock_id, date DESC);
