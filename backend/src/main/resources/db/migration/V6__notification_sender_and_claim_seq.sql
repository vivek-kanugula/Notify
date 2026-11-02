-- Notify / sender snapshot and monotonic claim fence (, / )
-- Additive forward migration. V1-V5 are untouched. Existing rows keep every value:
-- sender stays NULL (frozen at first claim for legacy rows), claim_seq becomes 0.
-- Nothing is backfilled or rewritten.

ALTER TABLE notifications ADD COLUMN sender VARCHAR(255);
ALTER TABLE notifications ADD COLUMN claim_seq INTEGER NOT NULL DEFAULT 0;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_claim_seq CHECK (claim_seq >= 0);
