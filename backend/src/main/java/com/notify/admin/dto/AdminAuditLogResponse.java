package com.notify.admin.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit log entry response for the admin audit log API ().
 */
public record AdminAuditLogResponse(
        Long id,
        UUID adminId,
        String adminEmail,
        String action,
        String targetType,
        String targetId,
        UUID tenantId,
        String tenantSlug,
        String reason,
        String details,
        String ipAddress,
        Instant createdAt
) {}
