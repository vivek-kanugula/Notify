-- V7 (/ ): DLQ materialization and quarantine.
--
-- Additive only. V1-V6 are applied and immutable; nothing in them is edited here.
--   * three nullable columns on dead_letter_entries, so a materialization can record which event produced the
--     current state and for which delivery cycle and dispatch generation;
--   * a partial unique index on last_event_id - a uniqueness constraint on a MUTABLE last-applied marker, not a
--     ledger of every event ever consumed;
--   * dlq_quarantine, for a DLT record (or an unprocessable notify.dlq.v1 record) that cannot become a business
--     entry: dead_letter_entries requires notification_id NOT NULL with a composite FK, and inventing an id is
--     forbidden.
--
-- Nothing is backfilled. A NULL source_delivery_cycle means UNKNOWN, never zero.

ALTER TABLE dead_letter_entries
    ADD COLUMN last_event_id         UUID,
    ADD COLUMN source_delivery_cycle INTEGER,
    ADD COLUMN source_generation     INTEGER;

CREATE UNIQUE INDEX uq_dlq_last_event ON dead_letter_entries (last_event_id)
    WHERE last_event_id IS NOT NULL;

CREATE TABLE dlq_quarantine (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    topic                   VARCHAR(128) NOT NULL,
    partition_number        INTEGER      NOT NULL,
    record_offset           BIGINT       NOT NULL,
    dlt_reason              VARCHAR(64)  NOT NULL,
    error_category          VARCHAR(64),
    exception_class         VARCHAR(255),
    payload_sha256          VARCHAR(64),
    payload_byte_count      INTEGER,
    -- UNTRUSTED payload copies, for triage only. Never used for authorization, filtering or as the tenant of
    -- record ().
    claimed_tenant_id       UUID,
    claimed_notification_id UUID,
    claimed_generation      INTEGER,
    -- Written only from a database lookup, when the association resolves.
    verified_tenant_id      UUID,
    verified_notification_id UUID,
    association             VARCHAR(32) NOT NULL
        CHECK (association IN ('RESOLVED', 'UNPARSABLE', 'UNKNOWN_NOTIFICATION', 'TENANT_MISMATCH')),
    status                  VARCHAR(32) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'DISCARDED')),
    resolution_note         VARCHAR(1024),
    resolved_by             VARCHAR(255),
    resolved_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A Kafka record coordinate triple is its identity: a redelivered record is an insert conflict, not a second
    -- quarantine row.
    CONSTRAINT uq_dlq_quarantine_record UNIQUE (topic, partition_number, record_offset)
);

CREATE INDEX idx_dlq_quarantine_open ON dlq_quarantine (created_at) WHERE status = 'OPEN';
CREATE INDEX idx_dlq_quarantine_verified ON dlq_quarantine (verified_notification_id)
    WHERE verified_notification_id IS NOT NULL;
