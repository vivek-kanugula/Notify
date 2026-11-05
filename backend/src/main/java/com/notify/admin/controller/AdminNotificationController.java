package com.notify.admin.controller;

import com.notify.admin.dto.AdminNotificationDetailResponse;
import com.notify.admin.dto.AdminNotificationListItemResponse;
import com.notify.admin.security.AdminSecurityUtils;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.delivery.service.DeliveryQueryService;
import com.notify.notification.service.NotificationQueryService;
import com.notify.notification.service.NotificationQueryService.NotificationView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin cross-tenant notification explorer (/ ). Strict admin boundary. No Hibernate tenant filter is
 * applied (none exists); queries filter only by the supplied parameters (M4).
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AdminNotificationController {

    private final NotificationQueryService notificationQueryService;
    private final DeliveryQueryService deliveryQueryService;

    public AdminNotificationController(NotificationQueryService notificationQueryService,
                                       DeliveryQueryService deliveryQueryService) {
        this.notificationQueryService = notificationQueryService;
        this.deliveryQueryService = deliveryQueryService;
    }

    @GetMapping
    public ResponseEntity<Page<AdminNotificationListItemResponse>> search(
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestParam(value = "tenantName", required = false) String tenantName,
            @RequestParam(value = "status", required = false) NotificationStatus status,
            @RequestParam(value = "channel", required = false) Channel channel,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            Authentication authentication) {
        AdminSecurityUtils.requireAdminPrincipal(authentication);

        page = Math.max(0, Math.min(page, 1000));
        size = Math.max(1, Math.min(size, 100));
        Instant fromInstant = parseInstant(from, "from");
        Instant toInstant = parseInstant(to, "to");
        if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED, "from must not be after to");
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AdminNotificationListItemResponse> result = notificationQueryService
                .adminSearch(tenantId, tenantName, channel, status, fromInstant, toInstant, pageable)
                .map(v -> new AdminNotificationListItemResponse(
                        v.id(), v.tenantId(), v.tenantName(), v.channel(), v.status(), v.recipient(), v.subject(), v.createdAt()));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{notificationId}")
    public ResponseEntity<AdminNotificationDetailResponse> detail(
            @PathVariable("notificationId") UUID notificationId,
            Authentication authentication) {
        AdminSecurityUtils.requireAdminPrincipal(authentication);

        NotificationView v = notificationQueryService.findForAdmin(notificationId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Notification not found"));

        AdminNotificationDetailResponse body = new AdminNotificationDetailResponse(
                v.id(), v.tenantId(), v.channel(), v.status(), v.recipient(), v.subject(),
                v.scheduledAt(), v.createdAt(), v.firstProcessingAt(), v.completedAt(),
                v.attemptCount(), v.lastErrorCode(), v.provider(), v.providerMessageId(), v.metadata(),
                deliveryQueryService.attemptsForNotification(v.tenantId(), v.id()),
                notificationQueryService.statusHistory(v.tenantId(), v.id()));
        return ResponseEntity.ok(body);
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
