package com.notify.admin.controller;

import com.notify.auth.service.AdminAuthService;
import com.notify.dlq.dto.DlqPage;
import com.notify.dlq.dto.QuarantineDetail;
import com.notify.dlq.dto.QuarantineResolutionRequest;
import com.notify.dlq.dto.QuarantineSummary;
import com.notify.dlq.model.QuarantineAssociation;
import com.notify.dlq.model.QuarantineStatus;
import com.notify.dlq.service.AdminDlqService;
import com.notify.dlq.service.AdminQuarantineService;
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
import java.util.Map;
import java.util.UUID;

/**
 * Admin inspection and resolution of quarantined records (/ , ).
 *
 * <p>These endpoints work when **no** {@code dead_letter_entries} row exists, which is why they exist separately:
 * an unparsable, unknown or mismatched record has no business entry, and under even a verified one has
 * none. There is deliberately **no replay** here and no bulk action.
 */
@RestController
@RequestMapping("/api/v1/admin/dlq/quarantine")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AdminDlqQuarantineController {

    private final AdminQuarantineService quarantineService;
    private final AdminDlqService dlqService;
    private final AdminAuthService adminAuthService;

    public AdminDlqQuarantineController(
            AdminQuarantineService quarantineService,
            AdminDlqService dlqService,
            AdminAuthService adminAuthService
    ) {
        this.quarantineService = quarantineService;
        this.dlqService = dlqService;
        this.adminAuthService = adminAuthService;
    }

    @GetMapping
    public ResponseEntity<DlqPage<QuarantineSummary>> list(
            @RequestParam(value = "association", required = false) String association,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "topic", required = false) String topic,
            @RequestParam(value = "dltReason", required = false) String dltReason,
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
        return ResponseEntity.ok(quarantineService.list(
                AdminDlqRequests.enumValue(association, QuarantineAssociation.class, "association"),
                AdminDlqRequests.enumValue(status, QuarantineStatus.class, "status"),
                topic, dltReason, fromInstant, toInstant, DlqCursor.decode(cursor), limit));
    }

    @GetMapping("/{quarantineId}")
    public ResponseEntity<QuarantineDetail> detail(
            @PathVariable("quarantineId") UUID quarantineId, Authentication authentication) {
        AdminDlqRequests.requireAdminPrincipal(authentication);
        return ResponseEntity.ok(quarantineService.detail(quarantineId));
    }

    @PostMapping("/{quarantineId}/resolve")
    public ResponseEntity<QuarantineDetail> resolve(
            @PathVariable("quarantineId") UUID quarantineId,
            @Valid @RequestBody QuarantineResolutionRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        Map<?, ?> principal = AdminDlqRequests.requireAdminPrincipal(authentication);
        UUID adminId = UUID.fromString((String) principal.get("sub"));
        AdminAuthService.AdminUserInfo info = adminAuthService.getAdminInfo(adminId);
        AdminDlqService.AdminActor actor = new AdminDlqService.AdminActor(
                adminId, info.email(), AdminDlqRequests.clientIp(httpRequest));
        return ResponseEntity.ok(quarantineService.resolve(quarantineId, request.note(), actor));
    }
}
