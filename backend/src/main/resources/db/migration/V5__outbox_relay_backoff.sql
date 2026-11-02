-- V5__outbox_relay_backoff.sql
-- Additive forward migration: add next_attempt_at and specialized partial indexes for outbox relay backoff and bidirectional fairness

-- Step 1: Add next_attempt_at column
ALTER TABLE outbox_events
    ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE;

-- Step 2: Backfill existing rows with created_at to preserve FIFO eligibility and original history
UPDATE outbox_events
    SET next_attempt_at = created_at
    WHERE next_attempt_at IS NULL;

-- Step 3: Enforce NOT NULL and establish DEFAULT CURRENT_TIMESTAMP for raw SQL/external inserts
ALTER TABLE outbox_events
    ALTER COLUMN next_attempt_at SET NOT NULL,
    ALTER COLUMN next_attempt_at SET DEFAULT CURRENT_TIMESTAMP;

-- Step 4: Build replacement partial indexes before considering removal of the V1 index
-- Retain old idx_outbox_pending from V1

-- Index 1: Partial index for fresh work (attempts = 0), ordered by created_at with deterministic id tie-breaker
CREATE INDEX idx_outbox_pending_fresh
    ON outbox_events (created_at ASC, id ASC)
    WHERE status = 'PENDING' AND attempts = 0;

-- Index 2: Partial index for due retries (attempts > 0), ordered by next_attempt_at, created_at, and id
CREATE INDEX idx_outbox_pending_retries
    ON outbox_events (next_attempt_at ASC, created_at ASC, id ASC)
    WHERE status = 'PENDING' AND attempts > 0;
