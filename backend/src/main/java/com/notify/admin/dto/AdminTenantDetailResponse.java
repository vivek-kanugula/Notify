package com.notify.admin.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Detailed tenant view for admin single-tenant endpoint ().
 * Includes all metadata, aggregate counts, and webhook configuration status.
 */
public record AdminTenantDetailResponse(
        UUID id,
        String slug,
        String name,
        String status,
        String statusReason,
        Instant statusChangedAt,
        Instant createdAt,
        Instant updatedAt,
        long userCount,
        long activeApiKeyCount,
        long templateCount,
        boolean hasWebhookConfigured
) {}
