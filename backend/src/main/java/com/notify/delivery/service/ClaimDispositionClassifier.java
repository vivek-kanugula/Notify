package com.notify.delivery.service;

import com.notify.common.model.NotificationStatus;
import com.notify.delivery.model.ClaimDisposition;
import com.notify.notification.dto.NotificationEvidence;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Pure and total decision table for a dispatch record whose claim updated zero rows (plan section 6.2).
 * Rows are tried in order and the first match wins. The dispatch envelope carries no delivery cycle, so none is compared.
 */
@Component
public class ClaimDispositionClassifier {

    public ClaimDisposition classify(Optional<NotificationEvidence> evidence, int eventGeneration, Instant now) {
        if (evidence.isEmpty()) {
            return ClaimDisposition.ROW_ABSENT;
        }
        NotificationEvidence row = evidence.get();
        NotificationStatus status = row.status();

        if (status == NotificationStatus.DELIVERED || status == NotificationStatus.FAILED
                || status == NotificationStatus.DLQ || status == NotificationStatus.CANCELLED) {
            return ClaimDisposition.TERMINAL;
        }
        if (row.dispatchGeneration() > eventGeneration) {
            return ClaimDisposition.STALE_GENERATION;
        }
        if (row.dispatchGeneration() < eventGeneration) {
            return ClaimDisposition.GENERATION_AHEAD;
        }
        if (status == NotificationStatus.RETRYING && row.nextRetryAt() != null && row.nextRetryAt().isAfter(now)) {
            return ClaimDisposition.RETRY_PENDING;
        }
        if (status == NotificationStatus.PROCESSING) {
            return ClaimDisposition.IN_PROGRESS_ELSEWHERE;
        }
        if (status == NotificationStatus.QUEUED
                || (status == NotificationStatus.RETRYING && row.nextRetryAt() != null && !row.nextRetryAt().isAfter(now))) {
            return ClaimDisposition.CLAIM_RACE;
        }
        // CREATED, SCHEDULED, RETRYING with NULL next_retry_at, anything else
        return ClaimDisposition.UNEXPECTED_STATE;
    }
}
