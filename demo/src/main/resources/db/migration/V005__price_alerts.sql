-- V005 - Price alerts.
--
-- An alert fires once when the last trade price crosses the threshold in the
-- given direction. `triggered_at` doubles as the fired flag so history is kept
-- rather than deleted.

CREATE TABLE IF NOT EXISTS price_alert (
    id           SERIAL PRIMARY KEY,
    stock_id     INTEGER NOT NULL REFERENCES stock (id) ON DELETE CASCADE,
    direction    TEXT NOT NULL,
    threshold    NUMERIC(14, 4) NOT NULL,
    note         TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    triggered_at TIMESTAMPTZ,
    -- Price that actually tripped the alert, recorded for the notification.
    triggered_price NUMERIC(14, 4),
    CONSTRAINT price_alert_direction_valid CHECK (direction IN ('ABOVE', 'BELOW')),
    CONSTRAINT price_alert_threshold_positive CHECK (threshold > 0)
);

-- The alert evaluator scans only alerts that have not fired yet.
CREATE INDEX IF NOT EXISTS price_alert_pending_idx
    ON price_alert (stock_id) WHERE triggered_at IS NULL;
