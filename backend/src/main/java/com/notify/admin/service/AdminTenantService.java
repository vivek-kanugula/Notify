package com.notify.admin.service;

import com.notify.admin.dto.AdminTenantDetailResponse;
import com.notify.admin.dto.AdminTenantListItemResponse;
import com.notify.admin.dto.AdminTenantResponse;
import com.notify.admin.dto.UpdateTenantStatusRequest;
import com.notify.admin.entity.AdminAuditLogEntity;
import com.notify.admin.repository.AdminAuditLogRepository;
import com.notify.apikey.repository.ApiKeyRepository;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.ApiKeyStatus;
import com.notify.common.model.TenantStatus;
import com.notify.notification.repository.NotificationRepository;
import com.notify.template.repository.TemplateRepository;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.event.TenantStatusChangedEvent;
import com.notify.tenant.repository.TenantRepository;
import com.notify.tenant.repository.TenantUserRepository;
import com.notify.webhook.repository.WebhookEndpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Platform administrator service for tenant lifecycle management.
 *
 * <p>Implements the tenant status transition matrix:
 * <ul>
 *   <li>{@code ACTIVE -> SUSPENDED} (valid)</li>
 *   <li>{@code ACTIVE -> REVOKED} (valid, triggers revocation cascade)</li>
 *   <li>{@code SUSPENDED -> ACTIVE} (valid)</li>
 *   <li>{@code SUSPENDED -> REVOKED} (valid, triggers revocation cascade)</li>
 *   <li>{@code REVOKED -> ACTIVE} (valid, credential invariant enforced)</li>
 *   <li>{@code REVOKED -> SUSPENDED} (invalid, 409)</li>
 *   <li>Same-status: idempotent 200 OK</li>
 * </ul>
 */
@Service
public class AdminTenantService {

    private static final Logger log = LoggerFactory.getLogger(AdminTenantService.class);

    private final TenantRepository tenantRepository;
    private final AdminAuditLogRepository adminAuditLogRepository;
    private final TenantUserRepository tenantUserRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final TemplateRepository templateRepository;
    private final WebhookEndpointRepository webhookEndpointRepository;
    private final NotificationRepository notificationRepository;
    private final TenantRevocationService tenantRevocationService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public AdminTenantService(TenantRepository tenantRepository,
                              AdminAuditLogRepository adminAuditLogRepository,
                              TenantUserRepository tenantUserRepository,
                              ApiKeyRepository apiKeyRepository,
                              TemplateRepository templateRepository,
                              WebhookEndpointRepository webhookEndpointRepository,
                              NotificationRepository notificationRepository,
                              TenantRevocationService tenantRevocationService,
                              ApplicationEventPublisher eventPublisher,
                              Clock clock) {
        this.tenantRepository = tenantRepository;
        this.adminAuditLogRepository = adminAuditLogRepository;
        this.tenantUserRepository = tenantUserRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.templateRepository = templateRepository;
        this.webhookEndpointRepository = webhookEndpointRepository;
        this.notificationRepository = notificationRepository;
        this.tenantRevocationService = tenantRevocationService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    // ─── Listing & Detail ───────────────────────────────────────────────────────────────────────────────

    /**
     * Paginated tenant listing with optional status and query filters ().
     */
    @Transactional(readOnly = true)
    public Page<AdminTenantListItemResponse> listTenants(TenantStatus status, String query, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<TenantEntity> tenants;
        if (status != null && query != null && !query.isBlank()) {
            tenants = tenantRepository.findByStatusAndQuery(status, query, pageable);
        } else if (status != null) {
            tenants = tenantRepository.findByStatus(status, pageable);
        } else if (query != null && !query.isBlank()) {
            tenants = tenantRepository.findByQuery(query, pageable);
        } else {
            tenants = tenantRepository.findAll(pageable);
        }

        return tenants.map(this::toListItemResponse);
    }

    /**
     * Tenant detail with aggregate counts ().
     */
    @Transactional(readOnly = true)
    public AdminTenantDetailResponse getTenantDetail(UUID tenantId) {
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Tenant not found"));

        long userCount = tenantUserRepository.countByTenantId(tenantId);
        long activeApiKeyCount = apiKeyRepository.countByTenantIdAndStatus(tenantId, ApiKeyStatus.ACTIVE);
        long templateCount = templateRepository.countByTenantIdAndDeletedAtIsNull(tenantId);
        boolean hasWebhookConfigured = webhookEndpointRepository.existsByTenantId(tenantId);

        return new AdminTenantDetailResponse(
                tenant.getId(),
                tenant.getSlug(),
                tenant.getName(),
                tenant.getStatus().name(),
                tenant.getStatusReason(),
                tenant.getStatusChangedAt(),
                tenant.getCreatedAt(),
                tenant.getUpdatedAt(),
                userCount,
                activeApiKeyCount,
                templateCount,
                hasWebhookConfigured
        );
    }

    // ─── Status Transition () ─────────────────────────────────────────────────────────────────────

    /**
     * Change tenant status with mandatory reason and audit trail.
     * Enforces the transition matrix and delegates to {@link TenantRevocationService}
     * when transitioning to REVOKED.
     */
    @Transactional
    public AdminTenantResponse updateTenantStatus(UUID tenantId,
                                                  UpdateTenantStatusRequest request,
                                                  UUID adminId,
                                                  String adminEmail,
                                                  String ipAddress) {
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Tenant not found"));

        TenantStatus currentStatus = tenant.getStatus();
        TenantStatus newStatus = request.status();

        // Same-status: idempotent 200 OK
        if (currentStatus == newStatus) {
            return toResponse(tenant);
        }

        // transition matrix enforcement
        validateTransition(currentStatus, newStatus);

        Instant now = clock.instant();

        // Determine audit action
        String action = switch (newStatus) {
            case SUSPENDED -> "TENANT_SUSPENDED";
            case REVOKED -> "TENANT_REVOKED";
            case ACTIVE -> "TENANT_REACTIVATED";
        };

        // Update tenant status
        tenant.setStatus(newStatus);
        tenant.setStatusReason(request.reason());
        tenant.setStatusChangedAt(now);
        tenant.setUpdatedAt(now);
        tenant = tenantRepository.save(tenant);

        // Publish event for cache eviction (and future downstream lifecycle listeners)
        eventPublisher.publishEvent(new TenantStatusChangedEvent(tenantId, currentStatus, newStatus));

        // Execute revocation cascade when transitioning to REVOKED ()
        if (newStatus == TenantStatus.REVOKED) {
            TenantRevocationService.RevocationResult result =
                    tenantRevocationService.executeRevocationCascade(tenantId, now);
            log.info("Tenant {} revocation cascade: cancelled={} notifications, revoked={} keys, revoked={} tokens, unscheduled={} triggers",
                    tenantId, result.cancelledNotifications(), result.revokedApiKeys(),
                    result.revokedRefreshTokens(), result.quartzTriggersUnscheduled());
        }

        // Write immutable admin audit log
        AdminAuditLogEntity auditLog = new AdminAuditLogEntity();
        auditLog.setAdminId(adminId);
        auditLog.setAdminEmail(adminEmail != null ? adminEmail : "admin@notify.local");
        auditLog.setAction(action);
        auditLog.setTargetType("TENANT");
        auditLog.setTargetId(tenantId.toString());
        auditLog.setTenantId(tenantId);
        auditLog.setTenantSlug(tenant.getSlug());
        auditLog.setReason(request.reason());
        auditLog.setIpAddress(ipAddress);
        auditLog.setCreatedAt(now);

        adminAuditLogRepository.save(auditLog);
        log.info("Admin {} changed tenant {} status from {} to {} (action={})",
                adminEmail, tenantId, currentStatus, newStatus, action);

        return toResponse(tenant);
    }

    /**
     * transition matrix validation.
     * Only {@code REVOKED -> SUSPENDED} is invalid; all other non-same transitions are permitted.
     */
    private void validateTransition(TenantStatus current, TenantStatus target) {
        if (current == TenantStatus.REVOKED && target == TenantStatus.SUSPENDED) {
            throw new NotifyException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot transition from REVOKED to SUSPENDED. Only REVOKED -> ACTIVE (reactivation) is permitted.");
        }
    }

    // ─── Response mapping ───────────────────────────────────────────────────────────────────────────────

    private AdminTenantResponse toResponse(TenantEntity entity) {
        return new AdminTenantResponse(
                entity.getId(),
                entity.getName(),
                entity.getSlug(),
                entity.getStatus().name(),
                entity.getStatusReason(),
                entity.getStatusChangedAt(),
                entity.getRateLimitPerMinute(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private AdminTenantListItemResponse toListItemResponse(TenantEntity entity) {
        UUID tenantId = entity.getId();
        long userCount = tenantUserRepository.countByTenantId(tenantId);
        long activeApiKeyCount = apiKeyRepository.countByTenantIdAndStatus(tenantId, ApiKeyStatus.ACTIVE);
        long templateCount = templateRepository.countByTenantIdAndDeletedAtIsNull(tenantId);

        return new AdminTenantListItemResponse(
                entity.getId(),
                entity.getSlug(),
                entity.getName(),
                entity.getStatus().name(),
                entity.getStatusReason(),
                entity.getStatusChangedAt(),
                entity.getCreatedAt(),
                userCount,
                activeApiKeyCount,
                templateCount
        );
    }
}
