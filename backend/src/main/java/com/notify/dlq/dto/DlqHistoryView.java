package com.notify.dlq.dto;

import java.time.Instant;

/** One status transition, as the admin detail shows it (). Reasons are already bounded to 64 characters. */
public record DlqHistoryView(
        String fromStatus,
        String toStatus,
        String actor,
        String reason,
        Instant occurredAt
) {
}
