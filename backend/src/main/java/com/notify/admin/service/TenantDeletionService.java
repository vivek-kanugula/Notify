package com.notify.admin.service;

import com.notify.admin.entity.AdminAuditLogEntity;
import com.notify.admin.repository.AdminAuditLogRepository;
import com.notify.apikey.cache.ApiKeyStatusCacheService;
import com.notify.auth.repository.RefreshTokenRepository;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.PrincipalType;
import com.notify.notification.repository.NotificationRepository;
import com.notify.quartz.service.NotificationSchedulerService;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import com.notify.tenant.repository.TenantUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Handles tenant deletion with slug confirmation, polymorphic session cleanup,
 * Quartz cancellation, audit snapshot, cascade deletion, and post-deletion verification (, ).
 */
@Service
public class TenantDeletionService {

    private static final Logger log = LoggerFactory.getLogger(TenantDeletionService.class);

    private final TenantRepository tenantRepository;
    private final TenantUserRepository tenantUserRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final NotificationRepository notificationRepository;
    private final AdminAuditLogRepository adminAuditLogRepository;
    private final NotificationSchedulerService schedulerService;
    private final ApiKeyStatusCacheService cacheService;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public TenantDeletionService(
            TenantRepository tenantRepository,
            TenantUserRepository tenantUserRepository,
            RefreshTokenRepository refreshTokenRepository,
            NotificationRepository notificationRepository,
            AdminAuditLogRepository adminAuditLogRepository,
            NotificationSchedulerService schedulerService,
            ApiKeyStatusCacheService cacheService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) StringRedisTemplate redisTemplate,
            Clock clock) {
        this.tenantRepository = tenantRepository;
        this.tenantUserRepository = tenantUserRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.notificationRepository = notificationRepository;
        this.adminAuditLogRepository = adminAuditLogRepository;
        this.schedulerService = schedulerService;
        this.cacheService = cacheService;
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    /**
     * Delete a tenant with slug confirmation, polymorphic cleanup, and audit persistence.
     *
     * @param tenantId  the tenant to delete
     * @param slug      the slug from X-Confirm-Tenant-Slug header
     * @param reason    the deletion reason
     * @param adminId   the admin performing the deletion
     * @param adminEmail the admin's email
     * @param ipAddress the client IP
     */
    @Transactional
    public void deleteTenant(UUID tenantId, String slug, String reason,
                             UUID adminId, String adminEmail, String ipAddress) {
        Instant now = clock.instant();

        // 1. Load tenant and validate slug confirmation ().
        // Uses pessimistic write lock to prevent concurrent modifications during deletion.
        TenantEntity tenant = tenantRepository.findByIdForUpdate(tenantId)
                .orElseThrow(() -> new NotifyException(ErrorCode.NOT_FOUND, "Tenant not found"));

        if (!tenant.getSlug().equals(slug)) {
            throw new NotifyException(ErrorCode.VALIDATION_FAILED,
                    "Tenant slug confirmation does not match");
        }

        // 2. Capture tenant user IDs before deletion ()
        List<UUID> capturedUserIds = tenantUserRepository.findIdsByTenantId(tenantId);
        log.info("Tenant {} deletion: captured {} user IDs for polymorphic cleanup",
                tenantId, capturedUserIds.size());

        // 3. Explicitly delete polymorphic refresh_tokens via live subquery and captured IDs (no FK to tenants)
        int deletedTokens = refreshTokenRepository.deleteAllForTenantUsers(tenantId);
        if (!capturedUserIds.isEmpty()) {
            refreshTokenRepository.deleteAllForPrincipals(PrincipalType.TENANT_USER, capturedUserIds);
        }
        log.info("Tenant {} deletion: deleted {} polymorphic refresh tokens", tenantId, deletedTokens);

        // 4. Unschedule all Quartz triggers for this tenant's notifications
        List<UUID> scheduledIds = notificationRepository.findScheduledIdsByTenantId(tenantId);
        for (UUID notificationId : scheduledIds) {
            schedulerService.cancelScheduledRelease(notificationId);
            schedulerService.cancelRetryTrigger(notificationId);
        }
        log.info("Tenant {} deletion: unscheduled {} Quartz triggers", tenantId, scheduledIds.size());

        // 5. Write immutable audit snapshot BEFORE deletion (survives cascade)
        AdminAuditLogEntity auditLog = new AdminAuditLogEntity();
        auditLog.setAdminId(adminId);
        auditLog.setAdminEmail(adminEmail);
        auditLog.setAction("TENANT_DELETED");
        auditLog.setTargetType("TENANT");
        auditLog.setTargetId(tenantId.toString());
        auditLog.setTenantId(tenantId);
        auditLog.setTenantSlug(tenant.getSlug());
        auditLog.setReason(reason != null && !reason.isBlank() ? reason : "Administrative tenant deletion");
        auditLog.setIpAddress(ipAddress);
        auditLog.setCreatedAt(now);
        adminAuditLogRepository.save(auditLog);

        // 6. Delete tenant entity — ON DELETE CASCADE cleans all FK-linked tables
        tenantRepository.delete(tenant);
        tenantRepository.flush();
        log.info("Tenant {} ({}) deleted by admin {}", tenantId, tenant.getSlug(), adminEmail);

        // 7. Post-deletion verification (step 7)
        verifyPostDeletion(tenantId, capturedUserIds);

        // 8. Register afterCommit hook for Redis cache purge
        registerAfterCommitCachePurge(tenantId);
    }

    private void verifyPostDeletion(UUID tenantId, List<UUID> capturedUserIds) {
        // Verify zero notification rows remain
        long notificationCount = notificationRepository.countByTenantId(tenantId);
        if (notificationCount > 0) {
            log.error("Post-deletion integrity violation: {} notification rows remain for tenant {}",
                    notificationCount, tenantId);
            throw new IllegalStateException(
                    "Post-deletion integrity violation: " + notificationCount + " notification rows remain");
        }

        // Verify zero refresh token rows remain for captured user IDs
        if (!capturedUserIds.isEmpty()) {
            long tokenCount = refreshTokenRepository.countByPrincipalTypeAndPrincipalIds(
                    PrincipalType.TENANT_USER, capturedUserIds);
            if (tokenCount > 0) {
                log.error("Post-deletion integrity violation: {} refresh token rows remain for tenant {} users",
                        tokenCount, tenantId);
                throw new IllegalStateException(
                        "Post-deletion integrity violation: " + tokenCount + " refresh token rows remain");
            }
        }

        // NOTE: a live `tenant_users` subquery check is useless here — the cascade has already removed
        // those rows, so the subquery matches nothing. The captured-ID check above is the authoritative
        // guard; the pessimistic tenant lock in deleteTenant blocks any new session from being issued
        // mid-deletion, so no user can appear after capture.

        // Verify audit log row persists
        long auditCount = adminAuditLogRepository.countByTenantIdAndAction(tenantId, "TENANT_DELETED");
        if (auditCount < 1) {
            log.error("Post-deletion integrity violation: TENANT_DELETED audit row missing for tenant {}", tenantId);
            throw new IllegalStateException("Post-deletion integrity violation: audit row missing");
        }

        log.info("Tenant {} post-deletion verification passed", tenantId);
    }

    private void registerAfterCommitCachePurge(UUID tenantId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cacheService.evictTenantStatus(tenantId);

                if (redisTemplate != null) {
                    try {
                        redisTemplate.delete("rl:" + tenantId);
                    } catch (Exception e) {
                        log.warn("Failed to purge rate limit cache for deleted tenant {}: {}",
                                tenantId, e.getMessage());
                    }
                }

                log.info("Tenant {} deletion: afterCommit cache purge complete", tenantId);
            }
        });
    }
}
