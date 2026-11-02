package com.notify.idempotency.model;

import com.notify.common.model.NotificationStatus;

import java.util.UUID;

/**
 * Result representing a verified idempotent replay.
 */
public record ReplayResult(
        UUID notificationId,
        NotificationStatus acceptanceStatus
) {}
