-- V013 - The label an imported holding came in under.
--
-- The ISIN, ticker or name the broker's file gave the row, whichever an
-- import remembers matches by. Kept so that a match fixed later, outside the
-- import, can be remembered under the same label and found by the next import.
-- Null for holdings imported before this column, and for synced accounts.

ALTER TABLE holding ADD COLUMN IF NOT EXISTS label TEXT;
