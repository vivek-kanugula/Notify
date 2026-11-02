package com.notify.admin.controller;

import com.notify.admin.dto.AdminAuditLogResponse;
import com.notify.admin.security.AdminSecurityUtils;
import com.notify.admin.service.AdminAuditService;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin audit log query endpoint ().
 * Strict admin-only access (ROLE_PLATFORM_ADMIN, aud=notify-admin).
 */
@RestController
@RequestMapping("/api/v1/admin/audit-log")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AdminAuditController {

    private final AdminAuditService adminAuditService;

    public AdminAuditController(AdminAuditService adminAuditService) {
        this.adminAuditService = adminAuditService;
    }

    /**
     * Paginated audit log listing with optional filters.
     */
    @GetMapping
    public ResponseEntity<Page<AdminAuditLogResponse>> listAuditLog(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            Authentication authentication) {

        AdminSecurityUtils.requireAdminPrincipal(authentication);

        // Clamp size to 1..100 and page to 0..1000
        size = Math.max(1, Math.min(size, 100));
        page = Math.max(0, Math.min(page, 1000));

        Instant fromInstant = parseInstant(from, "from");
        Instant toInstant = parseInstant(to, "to");

        if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, "from must not be after to");
        }

        return ResponseEntity.ok(adminAuditService.listAuditLog(
                tenantId, action, targetType, fromInstant, toInstant, page, size));
    }

    private Instant parseInstant(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException e) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, field + " must be an ISO-8601 instant");
        }
    }
}
