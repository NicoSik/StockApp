-- V006 - Search indexes.
--
-- Symbol search is prefix-anchored ("AAP" -> AAPL), which a text_pattern_ops
-- btree serves directly. Company search is substring ("apple" -> Apple Inc.),
-- which needs trigrams; pg_trgm ships with a standard PostgreSQL install but
-- creating an extension requires elevated rights, so a failure here degrades to
-- a sequential scan instead of breaking the migration.

CREATE INDEX IF NOT EXISTS stock_symbol_prefix_idx
    ON stock (upper(symbol) text_pattern_ops);

CREATE INDEX IF NOT EXISTS stock_company_prefix_idx
    ON stock (upper(company) text_pattern_ops);

DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_trgm;
    CREATE INDEX IF NOT EXISTS stock_company_trgm_idx
        ON stock USING gin (company gin_trgm_ops);
EXCEPTION
    WHEN insufficient_privilege OR undefined_file THEN
        RAISE NOTICE 'pg_trgm unavailable; company substring search will use a sequential scan.';
END $$;
