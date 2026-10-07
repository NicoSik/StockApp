-- V011 - Daily closing prices for priced holdings.
--
-- The holdings value history is rebuilt from these: each day's positions,
-- valued at that day's close. Keyed by symbol rather than instrument, because a
-- close is a fact about a listing: an instrument whose symbol is corrected
-- later must not keep the old symbol's prices.

CREATE TABLE IF NOT EXISTS instrument_close (
    symbol TEXT           NOT NULL,
    day    DATE           NOT NULL,
    close  NUMERIC(20, 6) NOT NULL,
    PRIMARY KEY (symbol, day),
    CONSTRAINT instrument_close_positive CHECK (close > 0)
);
