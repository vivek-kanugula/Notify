package com.notify.dlq.dto;

import com.notify.dlq.model.QuarantineAssociation;
import com.notify.dlq.model.QuarantineStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * The admin quarantine detail (/ , ).
 *
 * <p>Works when **no** {@code dead_letter_entries} row exists, which is the point: an unparsable, unknown or
 * mismatched record has no business entry to hang off. {@code payloadByteCount} and {@code payloadSha256} are all
 * that is kept of the record value - the value itself is never stored.
 *
 * <p>{@code notification} is present only for a verified association, and every field in it is named
 * {@code current...} because it is read at inspection time. {@code claimedGeneration} may be null, and there is
 * deliberately no cycle field.
 */
public record QuarantineDetail(
        UUID quarantineId,
        String topic,
        int partitionNumber,
        long recordOffset,
        String dltReason,
        String errorCategory,
        String exceptionClass,
        String payloadSha256,
        Integer payloadByteCount,
        QuarantineAssociation association,
        QuarantineStatus status,
        UUID claimedNotificationId,
        UUID claimedTenantId,
        Integer claimedGeneration,
        UUID verifiedNotificationId,
        UUID verifiedTenantId,
        DlqNotificationSnapshot notification,
        UUID relatedDlqEntryId,
        long relatedQuarantineCount,
        String resolutionNote,
        String resolvedBy,
        Instant resolvedAt,
        Instant createdAt
) {
}
