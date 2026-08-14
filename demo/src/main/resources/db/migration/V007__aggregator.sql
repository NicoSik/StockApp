-- V007 - Multi-broker portfolio aggregator.
--
-- Deliberately additive. The existing stock / portfolio / position / trade
-- tables continue to power the Alpaca watchlist and the simulated portfolio,
-- untouched. This is a second, separate world: real holdings imported from
-- brokers, valued in NOK.
--
-- The two are kept apart on purpose - mixing simulated money into a real
-- net-worth figure is not something anyone wants by accident.

-- An account is one broker relationship, or the manual bucket for things no
-- export covers (Norwegian funds, which neither DNB nor Nordnet export).
CREATE TABLE IF NOT EXISTS account (
    id         SERIAL PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE,
    broker     TEXT NOT NULL,
    kind       TEXT NOT NULL,
    currency   TEXT NOT NULL DEFAULT 'NOK',
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_kind_valid CHECK (kind IN ('IMPORTED', 'MANUAL'))
);

-- A tradable thing, independent of Alpaca's US-only universe: Oslo Børs
-- shares, Stockholm listings, ETFs and Norwegian funds all live here.
--
-- `verified` is the important flag. Neither broker export contains an ISIN, so
-- instruments are resolved by name or ticker and then cross-checked against the
-- price in the file. Nothing is treated as trustworthy until either that check
-- passed or a human confirmed it - the alternative is silently pricing a DNB
-- Bank holding as Dun & Bradstreet.
CREATE TABLE IF NOT EXISTS instrument (
    id           SERIAL PRIMARY KEY,
    isin         TEXT UNIQUE,
    symbol       TEXT,
    name         TEXT NOT NULL,
    currency     TEXT NOT NULL DEFAULT 'NOK',
    kind         TEXT NOT NULL DEFAULT 'STOCK',
    price_source TEXT NOT NULL DEFAULT 'NONE',
    verified     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT instrument_kind_valid
        CHECK (kind IN ('STOCK', 'ETF', 'FUND', 'CRYPTO', 'OTHER')),
    CONSTRAINT instrument_price_source_valid
        CHECK (price_source IN ('YAHOO', 'ALPACA', 'NONE'))
);

CREATE INDEX IF NOT EXISTS instrument_symbol_idx ON instrument (symbol);

-- Maps the exact string a broker uses to our instrument, so the mapping work
-- is done once per holding and never again. Nordnet writes "Oscar Health A",
-- DNB writes "OSCR"; both point at the same row.
CREATE TABLE IF NOT EXISTS instrument_alias (
    broker        TEXT NOT NULL,
    alias         TEXT NOT NULL,
    instrument_id INTEGER NOT NULL REFERENCES instrument (id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (broker, alias)
);

-- One import = one dated snapshot. Nothing is ever mutated in place, so a
-- re-import is always safe, an undo is a delete, and the value-over-time chart
-- falls out of the snapshot history without needing any transaction data -
-- which is just as well, because neither broker exports usable transactions.
CREATE TABLE IF NOT EXISTS snapshot (
    id              SERIAL PRIMARY KEY,
    account_id      INTEGER NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    as_of           DATE NOT NULL,
    source_file     TEXT,
    -- The total the broker itself reported, kept so a parsed file can be
    -- reconciled against its own stated total before being trusted.
    reported_total_nok NUMERIC(18, 2),
    imported_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, as_of)
);

CREATE INDEX IF NOT EXISTS snapshot_account_date_idx ON snapshot (account_id, as_of DESC);

CREATE TABLE IF NOT EXISTS holding (
    snapshot_id   INTEGER NOT NULL REFERENCES snapshot (id) ON DELETE CASCADE,
    instrument_id INTEGER NOT NULL REFERENCES instrument (id) ON DELETE CASCADE,
    quantity      NUMERIC(24, 8) NOT NULL,
    -- Average cost per share. Nordnet supplies this (GAV); DNB reports cost
    -- basis only at portfolio level, so it stays null there rather than being
    -- invented.
    avg_cost      NUMERIC(18, 6),
    currency      TEXT NOT NULL DEFAULT 'NOK',
    value_native  NUMERIC(18, 4),
    -- What the broker said it was worth in NOK at import time. This is the
    -- fallback valuation for anything that cannot be priced live, and both
    -- brokers happen to do the currency conversion for us.
    value_nok     NUMERIC(18, 2) NOT NULL,
    PRIMARY KEY (snapshot_id, instrument_id),
    CONSTRAINT holding_quantity_non_negative CHECK (quantity >= 0)
);

-- Norges Bank reference rates, cached per date.
--
-- `rate` is always stored normalised to "1 unit of base = rate NOK". The API
-- does not return it that way: SEK and DKK come quoted per hundred, with a
-- UNIT_MULT column saying so. Storing the raw figure would make a Swedish
-- holding worth a hundred times too much.
CREATE TABLE IF NOT EXISTS fx_rate (
    base     TEXT NOT NULL,
    quote    TEXT NOT NULL DEFAULT 'NOK',
    as_of    DATE NOT NULL,
    rate     NUMERIC(20, 10) NOT NULL,
    PRIMARY KEY (base, quote, as_of),
    CONSTRAINT fx_rate_positive CHECK (rate > 0)
);
