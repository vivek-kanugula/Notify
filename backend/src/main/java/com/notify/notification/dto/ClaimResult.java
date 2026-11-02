package com.notify.notification.dto;

import com.notify.common.model.NotificationStatus;

/**
 * Outcome of a worker claim attempt. Zero updated rows is a normal result ({@link Unclaimed}), never an exception.
 */
public sealed interface ClaimResult {

    /** The row moved to PROCESSING; {@code fromStatus} is QUEUED or RETRYING. */
    record Claimed(ClaimSnapshot snapshot, NotificationStatus fromStatus) implements ClaimResult {
    }

    /** No row matched the claim predicate (absent, wrong tenant/generation, not due, or not claimable). */
    record Unclaimed() implements ClaimResult {
        public static final Unclaimed INSTANCE = new Unclaimed();
    }
}
