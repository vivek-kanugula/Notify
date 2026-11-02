package com.notify.dlq.dto;

import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.common.model.DlqStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The admin DLQ detail (/ , , ).
 *
 * <p>The embedded lists are **bounded** to {@code notify.dlq.api.detail-embedded-limit} rows, newest first, each
 * with its own total and a {@code truncated} flag; the remainder is reachable through the cursored sub-resources.
 * {@code payloadSnapshot} is the dead-letter envelope only (), never notification content, and there is no
 * recipient field at all ().
 */
public record DlqEntryDetail(
        UUID entryId,
        UUID notificationId,
        UUID tenantId,
        String tenantSlug,
        Channel channel,
        DlqReason reason,
        String lastErrorCode,
        int attemptCount,
        DlqStatus status,
        int replayCount,
        Integer sourceDeliveryCycle,
        Integer sourceGeneration,
        String payloadSnapshot,
        String resolutionNote,
        String resolvedBy,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt,
        DlqNotificationSnapshot notification,
        List<DlqAttemptView> attempts,
        long attemptTotalCount,
        boolean attemptsTruncated,
        List<DlqHistoryView> history,
        long historyTotalCount,
        boolean historyTruncated,
        long quarantineRecordCount
) {
}
