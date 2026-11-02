package com.notify.notification.dto;

import com.notify.common.model.NotificationStatus;

import java.util.UUID;

/**
 * 202 Accepted response payload returned when a notification is durably accepted.
 */
public record NotificationAcceptanceResponse(
        UUID id,
        NotificationStatus status
) {}
