package com.notify.notification.dto;

import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;

import java.util.UUID;

/**
 * The locked notification state a dead-letter materialization is classified against (/ , ).
 *
 * <p>Read under {@code PESSIMISTIC_WRITE} <b>before any write</b>, so the consumer decides its outcome on a stable
 * row instead of attempting a lifecycle update and interpreting the row count. A DTO rather than the entity: the
 * DLQ feature never receives a notification entity.
 *
 * <p>Carries no recipient, subject, body or metadata - a materialization needs none of them, and the admin
 * projections must not expose them ().
 */
public record DeadLetterDecisionView(
        UUID id,
        UUID tenantId,
        Channel channel,
        NotificationStatus status,
        int deliveryCycle,
        int dispatchGeneration,
        int attemptCount,
        int maxAttempts
) {
}
