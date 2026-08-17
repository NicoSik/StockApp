-- V010 - Portfolio-level cost basis reported by a broker.
--
-- DNB's holdings sheet has no cost price per row, so per-holding gain cannot be
-- computed and is correctly left blank. But its Total sheet does carry Kostpris
-- and Urealisert for the account as a whole, and discarding those threw away
-- the only performance figure DNB actually provides, leaving an account with a
-- real unrealised gain showing a dash where the gain should be.
--
-- Stored on the snapshot rather than derived, because it is a number the broker
-- asserted at a point in time, not something this app can recompute.

ALTER TABLE snapshot ADD COLUMN IF NOT EXISTS reported_cost_basis_nok NUMERIC(18, 2);
