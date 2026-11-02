package com.notify.notification.dto;

import com.notify.common.model.Channel;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable view of a notification at the moment a worker claimed it. {@code attemptCount} is the
 * post-increment attempt number; {@code deferralCount} is the pre-result value. {@code claimSeq} is the
 * claim fence token that every result write must present ().
 *
 * <p>Contains recipient, subject and body: {@link #toString()} deliberately prints identifiers only.
 */
public record ClaimSnapshot(
        UUID id,
        UUID tenantId,
        int generation,
        int claimSeq,
        int deliveryCycle,
        int attemptCount,
        int maxAttempts,
        int deferralCount,
        Channel channel,
        String recipient,
        String subject,
        String body,
        String metadata,
        String traceContext,
        String sender,
        /**
         * When the notification most recently entered QUEUED. A retry release rewrites it, so it is scheduling
         * information and must not appear in anything that has to stay stable across the attempts of one cycle.
         */
        Instant queuedAt,
        /**
         * When the notification was accepted — immutable for the life of the row, and therefore the webhook payload's
         * {@code createdAt} (byte-identical body for every attempt of one delivery cycle).
         */
        Instant createdAt
) {
    @Override
    public String toString() {
        return "ClaimSnapshot[id=" + id + ", tenantId=" + tenantId + ", generation=" + generation
                + ", claimSeq=" + claimSeq + ", deliveryCycle=" + deliveryCycle + ", attemptCount=" + attemptCount + "]";
    }
}
