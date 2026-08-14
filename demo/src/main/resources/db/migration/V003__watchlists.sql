-- V003 - Real watchlists.
--
-- The legacy `watchlist` table was a two-column stub (TEXT id, TEXT name) with
-- no link to `stock` and no rows. Replace it with a proper list + items pair.
-- If it somehow contains rows they are preserved under `watchlist_legacy`
-- rather than destroyed.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'watchlist'
    ) THEN
        IF (SELECT count(*) FROM watchlist) = 0 THEN
            DROP TABLE watchlist CASCADE;
        ELSE
            ALTER TABLE watchlist RENAME TO watchlist_legacy;
        END IF;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS watchlist (
    id         SERIAL PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS watchlist_item (
    watchlist_id INTEGER NOT NULL REFERENCES watchlist (id) ON DELETE CASCADE,
    stock_id     INTEGER NOT NULL REFERENCES stock (id) ON DELETE CASCADE,
    sort_order   INTEGER NOT NULL DEFAULT 0,
    added_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (watchlist_id, stock_id)
);

CREATE INDEX IF NOT EXISTS watchlist_item_list_order_idx
    ON watchlist_item (watchlist_id, sort_order);
