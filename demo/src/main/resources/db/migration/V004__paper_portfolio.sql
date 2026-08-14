-- V004 - Local paper portfolio.
--
-- Entirely simulated and entirely local: no order ever reaches a broker. A
-- portfolio holds virtual cash; every fill is recorded in `trade`, and
-- `position` is the running aggregate maintained in the same transaction as
-- the trade that changed it.

CREATE TABLE IF NOT EXISTS portfolio (
    id            SERIAL PRIMARY KEY,
    name          TEXT NOT NULL UNIQUE,
    starting_cash NUMERIC(18, 4) NOT NULL,
    cash          NUMERIC(18, 4) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT portfolio_cash_non_negative CHECK (cash >= 0)
);

CREATE TABLE IF NOT EXISTS trade (
    id           BIGSERIAL PRIMARY KEY,
    portfolio_id INTEGER NOT NULL REFERENCES portfolio (id) ON DELETE CASCADE,
    stock_id     INTEGER REFERENCES stock (id) ON DELETE SET NULL,
    -- Denormalised so trade history survives a stock being removed.
    symbol       TEXT NOT NULL,
    side         TEXT NOT NULL,
    quantity     NUMERIC(18, 6) NOT NULL,
    price        NUMERIC(14, 4) NOT NULL,
    executed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    note         TEXT,
    CONSTRAINT trade_side_valid CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT trade_quantity_positive CHECK (quantity > 0),
    CONSTRAINT trade_price_positive CHECK (price > 0)
);

CREATE INDEX IF NOT EXISTS trade_portfolio_time_idx
    ON trade (portfolio_id, executed_at DESC);

CREATE TABLE IF NOT EXISTS position (
    portfolio_id INTEGER NOT NULL REFERENCES portfolio (id) ON DELETE CASCADE,
    stock_id     INTEGER NOT NULL REFERENCES stock (id) ON DELETE CASCADE,
    quantity     NUMERIC(18, 6) NOT NULL DEFAULT 0,
    -- Average cost per share of the shares currently held.
    avg_cost     NUMERIC(14, 4) NOT NULL DEFAULT 0,
    -- Cumulative profit/loss booked by sells, in currency units.
    realized_pnl NUMERIC(18, 4) NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (portfolio_id, stock_id),
    CONSTRAINT position_quantity_non_negative CHECK (quantity >= 0)
);
