package com.notify.notification.dto;

import com.notify.common.model.Channel;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Request payload for creating a notification.
 */
public record CreateNotificationRequest(
        @NotNull(message = "Channel is required")
        Channel channel,

        @NotNull(message = "Recipient is required")
        @Size(max = 255, message = "Recipient cannot exceed 255 characters")
        String recipient,

        @Size(max = 512, message = "Subject cannot exceed 512 characters")
        String subject,

        String body,

        UUID templateId,

        Integer templateVersion,

        Map<String, Object> templateVariables,

        Map<String, Object> metadata,

        @Size(max = 128, message = "Idempotency key cannot exceed 128 characters")
        String idempotencyKey,

        Instant scheduledAt
) {}
