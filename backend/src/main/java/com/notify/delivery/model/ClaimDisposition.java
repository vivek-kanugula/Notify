package com.notify.delivery.model;

/** What the worker does with a dispatch record whose claim returned zero rows (plan section 6.2). */
public enum ClaimDisposition {
    /** No row (or wrong tenant): log and acknowledge. */
    ROW_ABSENT,
    /** DELIVERED, FAILED, DLQ or CANCELLED: acknowledge. */
    TERMINAL,
    /** Database generation is newer than the event: the event is obsolete; acknowledge. */
    STALE_GENERATION,
    /** Event is newer than the database generation: not yet visible; retry (ORDERING). */
    GENERATION_AHEAD,
    /** RETRYING and not yet due: repair the Quartz trigger if missing and acknowledge. */
    RETRY_PENDING,
    /** PROCESSING at this generation: owned elsewhere or orphaned; retry then quarantine (UNRESOLVED). */
    IN_PROGRESS_ELSEWHERE,
    /** Claimable now but the claim lost a race; retry (ORDERING). */
    CLAIM_RACE,
    /** Any state a dispatch event should never meet; stop (NON_TRANSIENT). */
    UNEXPECTED_STATE
}
