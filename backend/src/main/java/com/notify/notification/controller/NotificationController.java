package com.notify.notification.controller;

import com.notify.common.context.TenantContext;
import com.notify.common.context.TenantPrincipal;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.notification.dto.*;
import com.notify.notification.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_notifications:send') or hasRole('TENANT_OWNER')")
    public ResponseEntity<NotificationAcceptanceResponse> createNotification(
            @Valid @RequestBody CreateNotificationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader
    ) {
        UUID tenantId = TenantContext.require().tenantId();
        NotificationService.AcceptanceResult result = notificationService.acceptNotification(
                tenantId, request, idempotencyKeyHeader
        );

        HttpHeaders headers = new HttpHeaders();
        if (result.isReplay()) {
            headers.set("Idempotent-Replay", "true");
        }

        if (result.rateLimitResult() != null) {
            headers.set("X-RateLimit-Limit", String.valueOf(result.rateLimitResult().limit()));
            headers.set("X-RateLimit-Remaining", String.valueOf(result.rateLimitResult().remaining()));
            headers.set("X-RateLimit-Reset", String.valueOf(result.rateLimitResult().resetSeconds()));
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .headers(headers)
                .body(new NotificationAcceptanceResponse(result.notificationId(), result.status()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_notifications:read') or hasRole('TENANT_OWNER')")
    public ResponseEntity<Page<NotificationResponse>> listNotifications(
            @RequestParam(required = false) Channel channel,
            @RequestParam(required = false) NotificationStatus status,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        UUID tenantId = TenantContext.require().tenantId();
        Page<NotificationResponse> page = notificationService.listNotifications(
                tenantId, channel, status, pageable
        );
        return ResponseEntity.ok(page);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_notifications:read') or hasRole('TENANT_OWNER')")
    public ResponseEntity<NotificationDetailResponse> getNotification(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require().tenantId();
        NotificationDetailResponse detail = notificationService.getNotification(tenantId, id);
        return ResponseEntity.ok(detail);
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAuthority('SCOPE_notifications:read') or hasRole('TENANT_OWNER')")
    public ResponseEntity<List<NotificationHistoryResponse>> getNotificationHistory(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require().tenantId();
        List<NotificationHistoryResponse> history = notificationService.getNotificationHistory(tenantId, id);
        return ResponseEntity.ok(history);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('SCOPE_notifications:cancel') or hasRole('TENANT_OWNER')")
    public ResponseEntity<CancelNotificationResponse> cancelNotification(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body
    ) {
        UUID tenantId = TenantContext.require().tenantId();
        String reason = body != null ? body.get("reason") : null;
        CancelNotificationResponse response = notificationService.cancelNotification(tenantId, id, reason);
        return ResponseEntity.ok(response);
    }
}
