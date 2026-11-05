package com.notify.admin.controller;

import com.notify.auth.service.AdminAuthService;
import com.notify.common.model.Channel;
import com.notify.common.model.DlqReason;
import com.notify.common.model.DlqStatus;
import com.notify.dlq.dto.DlqAttemptView;
import com.notify.dlq.dto.DlqEntryDetail;
import com.notify.dlq.dto.DlqEntrySummary;
import com.notify.dlq.dto.DlqHistoryView;
import com.notify.dlq.dto.DlqPage;
import com.notify.dlq.dto.DlqReplayResponse;
import com.notify.dlq.dto.DlqResolutionRequest;
import com.notify.dlq.service.AdminDlqService;
import com.notify.dlq.service.DlqCursor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin dead-letter inspection, replay and discard (/ , ).
 *
 * <p>Inside the existing {@code @Order(1)} admin chain, which requires {@code ROLE_PLATFORM_ADMIN}, and the
 * {@code notify-admin} audience is re-checked here exactly as {@code AdminTenantController} does: a tenant-owner
 * JWT or an API key carries neither, so neither can reach these handlers.
 *
 * <p>Every cross-tenant read stays inside {@code AdminDlqService}; no tenant-scoped repository method is reused
 * for them, and no tenant-facing endpoint gains a DLQ projection. **No new error code**: the frozen set covers
 * {@code NOT_FOUND}, {@code INVALID_STATE_TRANSITION} and {@code VALIDATION_FAILED}.
 */
@RestController
@RequestMapping("/api/v1/admin/dlq")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AdminDlqController {

    private final AdminDlqService dlqService;
    private final AdminAuthService adminAuthService;

    public AdminDlqController(AdminDlqService dlqService, AdminAuthService adminAuthService) {
        this.dlqService = dlqService;
        this.adminAuthService = adminAuthService;
    }

    @GetMapping
    public ResponseEntity<DlqPage<DlqEntrySummary>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "channel", required = false) String channel,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestParam(value = "tenantName", required = false) String tenantName,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "0") int limit,
            Authentication authentication
    ) {
        AdminDlqRequests.requireAdminPrincipal(authentication);
        Instant fromInstant = AdminDlqRequests.instant(from, "from");
        Instant toInstant = AdminDlqRequests.instant(to, "to");
        AdminDlqRequests.requireRange(fromInstant, toInstant);
        AdminDlqRequests.requireLimit(limit, dlqService.maxLimit());
        return ResponseEntity.ok(dlqService.list(
                AdminDlqRequests.enumValue(status, DlqStatus.class, "status"),
                AdminDlqRequests.enumValue(channel, Channel.class, "channel"),
                AdminDlqRequests.enumValue(reason, DlqReason.class, "reason"),
                tenantId, tenantName, fromInstant, toInstant, DlqCursor.decode(cursor), limit));
    }

    @GetMapping("/{entryId}")
    public ResponseEntity<DlqEntryDetail> detail(
            @PathVariable("entryId") UUID entryId, Authentication authentication) {
        AdminDlqRequests.requireAdminPrincipal(authentication);
        return ResponseEntity.ok(dlqService.detail(entryId));
    }

    @GetMapping("/{entryId}/attempts")
    public ResponseEntity<DlqPage<DlqAttemptView>> attempts(
            @PathVariable("entryId") UUID entryId,
            @RequestParam(value = "limit", required = false, defaultValue = "0") int limit,
            Authentication authentication
    ) {
        AdminDlqRequests.requireAdminPrincipal(authentication);
        AdminDlqRequests.requireLimit(limit, dlqService.maxLimit());
        return ResponseEntity.ok(dlqService.attempts(entryId, limit));
    }

    @GetMapping("/{entryId}/history")
    public ResponseEntity<DlqPage<DlqHistoryView>> history(
            @PathVariable("entryId") UUID entryId,
            @RequestParam(value = "limit", required = false, defaultValue = "0") int limit,
            Authentication authentication
    ) {
        AdminDlqRequests.requireAdminPrincipal(authentication);
        AdminDlqRequests.requireLimit(limit, dlqService.maxLimit());
        return ResponseEntity.ok(dlqService.history(entryId, limit));
    }

    @PostMapping("/{entryId}/replay")
    public ResponseEntity<DlqReplayResponse> replay(
            @PathVariable("entryId") UUID entryId,
            @Valid @RequestBody(required = false) DlqResolutionRequest.Replay request,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        AdminDlqService.AdminActor actor = actor(authentication, httpRequest);
        String note = request == null ? null : request.note();
        return ResponseEntity.ok(dlqService.replay(entryId, note, actor));
    }

    @PostMapping("/{entryId}/discard")
    public ResponseEntity<DlqEntryDetail> discard(
            @PathVariable("entryId") UUID entryId,
            @Valid @RequestBody DlqResolutionRequest.Discard request,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        AdminDlqService.AdminActor actor = actor(authentication, httpRequest);
        return ResponseEntity.ok(dlqService.discard(entryId, request.note(), actor));
    }

    /**
     * The acting admin, from the authenticated session only.
     *
     * <p>The audience check is the same one the tenant-status endpoint performs: a token minted for the tenant
     * audience cannot act here even if it somehow reached the chain.
     */
    private AdminDlqService.AdminActor actor(Authentication authentication, HttpServletRequest request) {
        java.util.Map<?, ?> principal = AdminDlqRequests.requireAdminPrincipal(authentication);
        UUID adminId = UUID.fromString((String) principal.get("sub"));
        AdminAuthService.AdminUserInfo info = adminAuthService.getAdminInfo(adminId);
        return new AdminDlqService.AdminActor(adminId, info.email(), AdminDlqRequests.clientIp(request));
    }
}
