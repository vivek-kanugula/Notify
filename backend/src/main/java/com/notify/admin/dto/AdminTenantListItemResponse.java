package com.notify.admin.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Tenant list item for paginated admin listing ().
 * Includes aggregate counts for users, active API keys, and templates.
 */
public record AdminTenantListItemResponse(
        UUID id,
        String slug,
        String name,
        String status,
        String statusReason,
        Instant statusChangedAt,
        Instant createdAt,
        long userCount,
        long activeApiKeyCount,
        long templateCount
) {}
