package com.notify.idempotency.model;

import com.notify.common.model.NotificationStatus;

import java.util.UUID;

/**
 * Value object cached in Redis for fast-path idempotency replay (`idem:{tenantId}:{key}`).
 */
public record CachedIdempotency(
        String status, // "IN_PROGRESS" | "DONE"
        String hash,
        UUID notificationId,
        NotificationStatus acceptanceStatus,
        String lockToken
) {
    public static CachedIdempotency inProgress(String lockToken) {
        return new CachedIdempotency("IN_PROGRESS", null, null, null, lockToken);
    }

    public static CachedIdempotency inProgress() {
        return new CachedIdempotency("IN_PROGRESS", null, null, null, null);
    }

    public static CachedIdempotency done(String hash, UUID notificationId, NotificationStatus acceptanceStatus) {
        return new CachedIdempotency("DONE", hash, notificationId, acceptanceStatus, null);
    }
}
