package com.notify.admin.service;

import com.notify.apikey.cache.ApiKeyStatusCacheService;
import com.notify.apikey.repository.ApiKeyRepository;
import com.notify.auth.repository.RefreshTokenRepository;
import com.notify.common.model.PrincipalType;
import com.notify.notification.repository.NotificationRepository;
import com.notify.quartz.service.NotificationSchedulerService;
import com.notify.tenant.repository.TenantUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Executes the durable revocation cascade within the administrative transaction ().
 *
 * <p>All database changes happen synchronously inside the caller's transaction.
 * Redis cache evictions are registered as {@code afterCommit} hooks so they never
 * fire on rollback.
 *
 * <p><b>Execution order matters</b>: status history INSERT must run before the bulk
 * cancel UPDATE so that {@code from_status} captures the actual pre-revocation status.
 */
@Service
public class TenantRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TenantRevocationService.class);

    private final NotificationRepository notificationRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TenantUserRepository tenantUserRepository;
    private final NotificationSchedulerService schedulerService;
    private final ApiKeyStatusCacheService cacheService;
    private final StringRedisTemplate redisTemplate;

    public TenantRevocationService(
            NotificationRepository notificationRepository,
            ApiKeyRepository apiKeyRepository,
            RefreshTokenRepository refreshTokenRepository,
            TenantUserRepository tenantUserRepository,
            NotificationSchedulerService schedulerService,
            ApiKeyStatusCacheService cacheService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.notificationRepository = notificationRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tenantUserRepository = tenantUserRepository;
        this.schedulerService = schedulerService;
        this.cacheService = cacheService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Execute the full revocation cascade for a tenant within the current transaction.
     *
     * <p>Must be called after the tenant status has been set to {@code REVOKED} and
     * persisted (but before the transaction commits).
     *
     * @param tenantId the tenant being revoked
     * @param now      the authoritative timestamp (from Clock)
     * @return a summary of actions taken
     */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public RevocationResult executeRevocationCascade(UUID tenantId, Instant now) {
        // 1. Capture scheduled notification IDs for Quartz unscheduling (before status changes)
        List<UUID> scheduledIds = notificationRepository.findScheduledIdsByTenantId(tenantId);

        // 2. Atomic bulk cancel + history (single locking CTE; no worker-claim interleave, no divergence)
        int cancelledNotifications = notificationRepository.bulkCancelAndRecordForTenantRevocation(tenantId, now);
        int historyRows = cancelledNotifications;
        log.info("Tenant {} revocation: cancelled {} non-terminal notifications (history rows written atomically)",
                tenantId, cancelledNotifications);

        // 4. Unschedule Quartz triggers for SCHEDULED notifications
        int quartzCancelled = 0;
        for (UUID notificationId : scheduledIds) {
            schedulerService.cancelScheduledRelease(notificationId);
            schedulerService.cancelRetryTrigger(notificationId);
            quartzCancelled++;
        }
        log.info("Tenant {} revocation: unscheduled {} Quartz triggers", tenantId, quartzCancelled);

        // 5. Capture key hashes for Redis eviction (before revoking changes their status)
        List<String> keyHashes = apiKeyRepository.findKeyHashesByTenantId(tenantId);

        // 6. Bulk-revoke all active API keys
        int revokedKeys = apiKeyRepository.revokeAllByTenantId(tenantId, now);
        log.info("Tenant {} revocation: revoked {} API keys", tenantId, revokedKeys);

        // 7. Bulk-revoke all refresh tokens for tenant users (via live subquery and user IDs)
        int revokedTokens = refreshTokenRepository.revokeAllForTenantUsers(tenantId, now);
        List<UUID> userIds = tenantUserRepository.findIdsByTenantId(tenantId);
        if (!userIds.isEmpty()) {
            refreshTokenRepository.revokeAllForPrincipals(
                    PrincipalType.TENANT_USER, userIds, now);
        }
        log.info("Tenant {} revocation: revoked {} refresh tokens for {} users",
                tenantId, revokedTokens, userIds.size());

        // 8. Register afterCommit hook for Redis cache evictions
        registerAfterCommitCacheEvictions(tenantId, keyHashes);

        return new RevocationResult(cancelledNotifications, historyRows, revokedKeys, revokedTokens, quartzCancelled);
    }

    private void registerAfterCommitCacheEvictions(UUID tenantId, List<String> keyHashes) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // Evict tenant status cache
                cacheService.evictTenantStatus(tenantId);

                // Evict all API key caches for the tenant
                for (String keyHash : keyHashes) {
                    cacheService.evictApiKey(keyHash);
                }

                // Evict rate limit counter
                if (redisTemplate != null) {
                    try {
                        redisTemplate.delete("rl:" + tenantId);
                    } catch (Exception e) {
                        log.warn("Failed to evict rate limit cache for tenant {}: {}", tenantId, e.getMessage());
                    }
                }

                log.info("Tenant {} revocation: afterCommit cache evictions complete ({} API key entries)",
                        tenantId, keyHashes.size());
            }
        });
    }

    /**
     * Summary of the revocation cascade actions.
     */
    public record RevocationResult(
            int cancelledNotifications,
            int statusHistoryRows,
            int revokedApiKeys,
            int revokedRefreshTokens,
            int quartzTriggersUnscheduled
    ) {}
}
