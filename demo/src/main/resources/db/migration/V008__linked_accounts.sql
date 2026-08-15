-- V008 - Accounts linked over an API, rather than fed by a file.
--
-- eToro is the first broker here with a real personal API, so its holdings
-- arrive live instead of via an export. That changes two things:
--
--   * the account is LINKED rather than IMPORTED
--   * the valuation comes from eToro itself, not from re-pricing the position
--
-- The second one matters more than it looks. eToro mixes plain shares with
-- leveraged CFDs and copy portfolios, none of which are "N shares of X" that
-- could be priced from a ticker. Taking eToro's own valuation sidesteps
-- modelling any of it: they know what a leveraged short is worth, and the only
-- work left is converting their account currency into NOK.

ALTER TABLE account DROP CONSTRAINT IF EXISTS account_kind_valid;
ALTER TABLE account ADD CONSTRAINT account_kind_valid
    CHECK (kind IN ('IMPORTED', 'MANUAL', 'LINKED'));

ALTER TABLE instrument DROP CONSTRAINT IF EXISTS instrument_price_source_valid;
ALTER TABLE instrument ADD CONSTRAINT instrument_price_source_valid
    CHECK (price_source IN ('YAHOO', 'ALPACA', 'ETORO', 'NONE'));

-- eToro identifies instruments by an opaque numeric id, not a ticker, so the
-- mapping has to be stored to survive a restart and to avoid re-resolving it.
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS external_id TEXT;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS external_source TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS instrument_external_uidx
    ON instrument (external_source, external_id)
    WHERE external_id IS NOT NULL;

-- A CFD is not a shareholding, and a short is not a long. Recording both keeps
-- the holdings table honest rather than presenting leveraged exposure as though
-- it were ordinary stock.
ALTER TABLE holding ADD COLUMN IF NOT EXISTS leverage NUMERIC(10, 2);
ALTER TABLE holding ADD COLUMN IF NOT EXISTS direction TEXT;

ALTER TABLE holding DROP CONSTRAINT IF EXISTS holding_direction_valid;
ALTER TABLE holding ADD CONSTRAINT holding_direction_valid
    CHECK (direction IS NULL OR direction IN ('LONG', 'SHORT'));
