package com.notify.notification.dto;

import com.notify.common.model.Channel;

import java.time.Instant;
import java.util.UUID;

/**
 * One notification that has been in PROCESSING longer than the recovery cutoff (/ , ).
 *
 * <p>A DTO, not an entity: {@code StuckProcessingReaper} lives in the delivery feature and reaches the notification
 * feature only through {@code NotificationService}, so no entity or repository crosses that boundary - the project
 * rule that features call each other through service interfaces, never repositories.
 *
 * <p>Every field here is a <b>snapshot</b>, and none of it is trusted as current. The recovery predicates re-test
 * the status, the cutoff, the generation, the {@code claim_seq} and the attempt budget inside their own conditional
 * updates, so a claim that moved on between the scan and the reap simply updates zero rows. The counts are carried
 * for the reaper's log and metrics, not for its decision.
 *
 * @param processingStartedAt also the keyset cursor, paired with {@code id}
 */
public record StuckClaimCandidate(
        UUID tenantId,
        UUID id,
        Channel channel,
        int dispatchGeneration,
        int claimSeq,
        int attemptCount,
        int maxAttempts,
        Instant processingStartedAt
) {
}
