package com.notify.admin.dto;

import java.time.Instant;
import java.util.UUID;

public record AdminTenantResponse(
        UUID id,
        String name,
        String slug,
        String status,
        String statusReason,
        Instant statusChangedAt,
        int rateLimitPerMinute,
        Instant createdAt,
        Instant updatedAt
) {}
