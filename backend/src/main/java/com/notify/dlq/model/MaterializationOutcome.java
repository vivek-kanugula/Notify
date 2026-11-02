package com.notify.dlq.model;

/**
 * The five outcomes of consuming one authoritative {@code notify.dlq.v1} event (, section 2.2 of the plan).
 *
 * <p>Every reachable state maps to exactly one of these, decided on locked rows <b>before</b> any lifecycle write.
 * {@link #DUPLICATE} and {@link #STALE} are the only silent no-ops, and both are proofs: the event is recorded as
 * applied, or the row demonstrably moved past this cycle. Anything else that cannot be applied is
 * {@link #QUARANTINE}, so "this record cannot be explained" never looks like "this record was already handled".
 */
public enum MaterializationOutcome {

    /** Entry inserted, notification FAILED to DLQ, one history row. */
    MATERIALIZED,

    /** A later cycle failed again: the existing entry is reopened. {@code replay_count} is untouched (). */
    REOPENED,

    /** The entry already records this event id. Nothing written. */
    DUPLICATE,

    /** The notification demonstrably moved past this cycle. Nothing written. */
    STALE,

    /** Inconsistent: a quarantine row is written and the notification, entry, history and replay_count are left alone. */
    QUARANTINED
}
