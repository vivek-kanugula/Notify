package com.notify.notification.dto;

import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Summary DTO for paginated notification listings (omits rendered body for throughput).
 */
public record NotificationResponse(
        UUID id,
        Channel channel,
        NotificationStatus status,
        String recipient,
        String subject,
        Instant scheduledAt,
        Instant createdAt
) {}
