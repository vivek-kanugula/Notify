package com.notify.admin.dto;

import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;

import java.time.Instant;
import java.util.UUID;

/** Summary row for the admin cross-tenant notification explorer (). */
public record AdminNotificationListItemResponse(
        UUID id,
        UUID tenantId,
        Channel channel,
        NotificationStatus status,
        String recipient,
        String subject,
        Instant createdAt
) {}
