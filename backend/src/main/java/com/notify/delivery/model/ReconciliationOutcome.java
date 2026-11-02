package com.notify.delivery.model;

/** Interpretation of the evidence row after an ambiguous result commit (plan section 7). */
public enum ReconciliationOutcome {
    /** The notification row no longer exists. */
    ABSENT,
    /** Generation or claim_seq differs: a newer claim superseded this one. */
    SUPERSEDED,
    /** Still PROCESSING at this claim: the result was not visible when read. */
    UNRESOLVED,
    /** Not PROCESSING at this (generation, claim_seq): this claim's own result is durable. */
    COMMITTED
}
