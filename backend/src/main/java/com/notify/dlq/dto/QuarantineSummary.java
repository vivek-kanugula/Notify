package com.notify.dlq.dto;

import com.notify.dlq.model.QuarantineAssociation;
import com.notify.dlq.model.QuarantineStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the admin quarantine list (/ , ).
 *
 * <p>The {@code claimed...} fields are **untrusted payload copies**, and are named so a console cannot present them
 * as fact; the {@code verified...} fields come from a database lookup. There is no cycle field, because the
 * dispatch envelope never carried one.
 */
public record QuarantineSummary(
        UUID quarantineId,
        String topic,
        int partitionNumber,
        long recordOffset,
        String dltReason,
        String errorCategory,
        QuarantineAssociation association,
        QuarantineStatus status,
        UUID claimedNotificationId,
        UUID claimedTenantId,
        UUID verifiedNotificationId,
        UUID verifiedTenantId,
        Instant createdAt
) {
}
