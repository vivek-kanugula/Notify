package com.notify.notification.dto;

import com.notify.common.model.NotificationStatus;

import java.time.Instant;

/**
 * One consistent, single-row observation of a notification's delivery-relevant state.
 * Carries no recipient, subject, or body. Used for unclaimed-record disposition and ambiguous-commit reconciliation.
 */
public record NotificationEvidence(
        NotificationStatus status,
        int dispatchGeneration,
        int claimSeq,
        int deliveryCycle,
        int attemptCount,
        int maxAttempts,
        int deferralCount,
        Instant nextRetryAt,
        String lastErrorCode
) {
}
