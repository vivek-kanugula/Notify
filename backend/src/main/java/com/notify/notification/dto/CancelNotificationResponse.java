package com.notify.notification.dto;

import com.notify.common.model.NotificationStatus;

import java.util.UUID;

/**
 * Response returned after successful cancellation.
 */
public record CancelNotificationResponse(
        UUID id,
        NotificationStatus status
) {}
