package com.notify.notification.dto;

import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Detailed DTO including rendered body snapshot and lifecycle metadata.
 */
public record NotificationDetailResponse(
        UUID id,
        UUID tenantId,
        Channel channel,
        NotificationStatus status,
        String recipient,
        String subject,
        String body,
        UUID templateVersionId,
        Map<String, Object> templateVariables,
        Map<String, Object> metadata,
        String idempotencyKey,
        Instant scheduledAt,
        Instant queuedAt,
        Instant processingStartedAt,
        Instant completedAt,
        int attemptCount,
        int maxAttempts,
        String lastErrorCode,
        String provider,
        int dispatchGeneration,
        int deliveryCycle,
        Instant createdAt,
        Instant updatedAt
) {}
