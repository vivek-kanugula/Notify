package com.notify.dlq.dto;

import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.common.model.DlqStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the admin DLQ list (/ , ).
 *
 * <p><b>No recipient, subject, body, metadata, URL, secret or provider snippet</b> (): the projection is
 * narrow by construction rather than sanitized, so there is nothing to forget to mask.
 */
public record DlqEntrySummary(
        UUID entryId,
        UUID notificationId,
        UUID tenantId,
        String tenantSlug,
        String tenantName,
        Channel channel,
        DlqReason reason,
        String lastErrorCode,
        int attemptCount,
        DlqStatus status,
        int replayCount,
        Instant createdAt,
        Instant updatedAt
) {
    public DlqEntrySummary(
            UUID entryId, UUID notificationId, UUID tenantId, String tenantSlug,
            Channel channel, DlqReason reason, String lastErrorCode, int attemptCount,
            DlqStatus status, int replayCount, Instant createdAt, Instant updatedAt
    ) {
        this(entryId, notificationId, tenantId, tenantSlug, null, channel, reason, lastErrorCode, attemptCount, status, replayCount, createdAt, updatedAt);
    }
}
