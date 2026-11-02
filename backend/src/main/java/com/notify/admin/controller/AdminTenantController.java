package com.notify.admin.controller;

import com.notify.admin.dto.AdminTenantDetailResponse;
import com.notify.admin.dto.AdminTenantListItemResponse;
import com.notify.admin.dto.AdminTenantResponse;
import com.notify.admin.dto.UpdateTenantStatusRequest;
import com.notify.admin.security.AdminSecurityUtils;
import com.notify.admin.service.AdminTenantService;
import com.notify.admin.service.TenantDeletionService;
import com.notify.auth.service.AdminAuthService;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.TenantStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Platform administrator endpoints for tenant lifecycle management.
 * Protected by Order(1) admin security chain + method security (ROLE_PLATFORM_ADMIN).
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AdminTenantController {

    private final AdminTenantService adminTenantService;
    private final TenantDeletionService tenantDeletionService;
    private final AdminAuthService adminAuthService;

    public AdminTenantController(AdminTenantService adminTenantService,
                                  TenantDeletionService tenantDeletionService,
                                  AdminAuthService adminAuthService) {
        this.adminTenantService = adminTenantService;
        this.tenantDeletionService = tenantDeletionService;
        this.adminAuthService = adminAuthService;
    }

    /**
     * Paginated tenant listing with optional status and query filters ().
     */
    @GetMapping
    public ResponseEntity<Page<AdminTenantListItemResponse>> listTenants(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "status", required = false) TenantStatus status,
            @RequestParam(value = "query", required = false) String query,
            Authentication authentication) {

        AdminSecurityUtils.requireAdminPrincipal(authentication);

        // Clamp size to 1..100 and page to 0..1000
        size = Math.max(1, Math.min(size, 100));
        page = Math.max(0, Math.min(page, 1000));

        return ResponseEntity.ok(adminTenantService.listTenants(status, query, page, size));
    }

    /**
     * Tenant detail with aggregate counts ().
     */
    @GetMapping("/{tenantId}")
    public ResponseEntity<AdminTenantDetailResponse> getTenant(
            @PathVariable("tenantId") UUID tenantId,
            Authentication authentication) {

        AdminSecurityUtils.requireAdminPrincipal(authentication);

        return ResponseEntity.ok(adminTenantService.getTenantDetail(tenantId));
    }

    /**
     * Update tenant status (ACTIVE, SUSPENDED, REVOKED) with required reason and immutable audit log ().
     * Enforces transition matrix and delegates revocation cascade for REVOKED transitions.
     */
    @PatchMapping("/{tenantId}/status")
    public ResponseEntity<AdminTenantResponse> updateTenantStatus(
            @PathVariable("tenantId") UUID tenantId,
            @Valid @RequestBody UpdateTenantStatusRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        Map<?, ?> principal = AdminSecurityUtils.requireAdminPrincipal(authentication);

        UUID adminId = AdminSecurityUtils.adminId(principal);
        AdminAuthService.AdminUserInfo adminInfo = adminAuthService.getAdminInfo(adminId);

        String ip = AdminSecurityUtils.clientIp(httpRequest);
        AdminTenantResponse response = adminTenantService.updateTenantStatus(
                tenantId, request, adminId, adminInfo.email(), ip);

        return ResponseEntity.ok(response);
    }

    /**
     * Delete a tenant with slug confirmation (, , ).
     *
     * <p>Requires {@code X-Confirm-Tenant-Slug} header matching the tenant's slug exactly.
     * Missing header returns 400, mismatched slug returns 422.
     */
    @DeleteMapping("/{tenantId}")
    public ResponseEntity<Void> deleteTenant(
            @PathVariable("tenantId") UUID tenantId,
            @RequestHeader(value = "X-Confirm-Tenant-Slug", required = false) String confirmSlug,
            @RequestParam(value = "reason", required = false) String reason,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        Map<?, ?> principal = AdminSecurityUtils.requireAdminPrincipal(authentication);

        // slug header is required
        if (confirmSlug == null || confirmSlug.isBlank()) {
            throw new NotifyException(ErrorCode.MISSING_REQUEST_HEADER,
                    "X-Confirm-Tenant-Slug header is required for tenant deletion");
        }

        UUID adminId = AdminSecurityUtils.adminId(principal);
        AdminAuthService.AdminUserInfo adminInfo = adminAuthService.getAdminInfo(adminId);

        String ip = AdminSecurityUtils.clientIp(httpRequest);
        tenantDeletionService.deleteTenant(tenantId, confirmSlug, reason,
                adminId, adminInfo.email(), ip);

        return ResponseEntity.noContent().build();
    }
}
