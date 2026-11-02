package com.notify.notification.event;

import com.notify.common.model.Actor;
import com.notify.common.model.NotificationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published strictly after transaction commit on every notification status transition.
 */
public record NotificationTransitionedEvent(
        UUID tenantId,
        UUID notificationId,
        NotificationStatus fromStatus,
        NotificationStatus toStatus,
        Actor actor,
        int generation,
        Instant occurredAt
) {}
