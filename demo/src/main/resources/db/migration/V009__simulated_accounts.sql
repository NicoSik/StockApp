-- V009 - Mark accounts whose money is not real.
--
-- An eToro demo account reports a portfolio exactly like a real one, cash and
-- all. Synced without a flag, its practice balance lands in the combined total
-- and a net worth reads several times what it is - the first demo sync here
-- turned 616k NOK into 1.56M.
--
-- This is the same rule the paper portfolio already follows: simulated money is
-- shown, never counted. The flag lives on the account rather than being derived
-- from configuration, because ETORO_DEMO can be flipped later while a snapshot
-- taken under it stays in the database forever.

ALTER TABLE account ADD COLUMN IF NOT EXISTS simulated BOOLEAN NOT NULL DEFAULT FALSE;

-- Any eToro demo account already synced before this migration.
UPDATE account SET simulated = TRUE
 WHERE broker = 'ETORO' AND lower(name) LIKE '%demo%';
