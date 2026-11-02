package com.notify.apikey.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.apikey.entity.ApiKeyEntity;
import com.notify.apikey.repository.ApiKeyRepository;
import com.notify.common.model.TenantStatus;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.event.TenantStatusChangedEvent;
import com.notify.tenant.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Caches API key metadata and tenant lifecycle status in Redis per .
 *
 * Cache keys & policies:
 * - {@code apikey:{keyHash}}: 60s TTL. DB fallback on miss/error. Evicted on key revocation.
 * - {@code tenant:status:{tenantId}}: 30s TTL. DB fallback on miss/error. Evicted on {@link TenantStatusChangedEvent}.
 *
 * Fail-open policy (): If Redis is unavailable, requests query PostgreSQL directly.
 */
@Service
public class ApiKeyStatusCacheService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyStatusCacheService.class);

    private static final String API_KEY_PREFIX = "apikey:";
    private static final String TENANT_STATUS_PREFIX = "tenant:status:";
    private static final Duration API_KEY_TTL = Duration.ofSeconds(60);
    private static final Duration TENANT_STATUS_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;
    private final ApiKeyRepository apiKeyRepository;
    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper;

    public ApiKeyStatusCacheService(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            ApiKeyRepository apiKeyRepository,
            TenantRepository tenantRepository) {
        this.redisTemplate = redisTemplate;
        this.apiKeyRepository = apiKeyRepository;
        this.tenantRepository = tenantRepository;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    /**
     * Resolve API key metadata by key hash.
     * Checks Redis first; falls back to DB on miss or Redis failure.
     */
    public Optional<CachedApiKey> getApiKey(String keyHash) {
        if (redisTemplate != null) {
            try {
                String cachedJson = redisTemplate.opsForValue().get(API_KEY_PREFIX + keyHash);
                if (cachedJson != null) {
                    CachedApiKey cached = objectMapper.readValue(cachedJson, CachedApiKey.class);
                    return Optional.of(cached);
                }
            } catch (Exception e) {
                log.warn("Redis error fetching API key cache (falling back to DB): {}", e.getMessage());
            }
        }

        // DB fallback
        Optional<ApiKeyEntity> entityOpt = apiKeyRepository.findByKeyHash(keyHash);
        if (entityOpt.isEmpty()) {
            return Optional.empty();
        }

        ApiKeyEntity entity = entityOpt.get();
        CachedApiKey cached = new CachedApiKey(
                entity.getId(),
                entity.getTenantId(),
                entity.getStatus().name(),
                List.of(entity.getScopes()),
                entity.getExpiresAt()
        );

        // Populate cache asynchronously/opportunistically
        if (redisTemplate != null) {
            try {
                String json = objectMapper.writeValueAsString(cached);
                redisTemplate.opsForValue().set(API_KEY_PREFIX + keyHash, json, API_KEY_TTL);
            } catch (Exception e) {
                log.warn("Redis error writing API key cache: {}", e.getMessage());
            }
        }

        return Optional.of(cached);
    }

    /**
     * Resolve tenant status by tenant ID.
     * Checks Redis first; falls back to DB on miss or Redis failure.
     */
    public Optional<TenantStatus> getTenantStatus(UUID tenantId) {
        if (redisTemplate != null) {
            try {
                String cachedStatus = redisTemplate.opsForValue().get(TENANT_STATUS_PREFIX + tenantId);
                if (cachedStatus != null) {
                    return Optional.of(TenantStatus.valueOf(cachedStatus));
                }
            } catch (Exception e) {
                log.warn("Redis error fetching tenant status cache (falling back to DB): {}", e.getMessage());
            }
        }

        // DB fallback
        Optional<TenantEntity> tenantOpt = tenantRepository.findById(tenantId);
        if (tenantOpt.isEmpty()) {
            return Optional.empty();
        }

        TenantStatus status = tenantOpt.get().getStatus();

        // Populate cache
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(TENANT_STATUS_PREFIX + tenantId, status.name(), TENANT_STATUS_TTL);
            } catch (Exception e) {
                log.warn("Redis error writing tenant status cache: {}", e.getMessage());
            }
        }

        return Optional.of(status);
    }

    /**
     * Evict an API key from Redis (e.g. upon revocation or rotation).
     */
    public void evictApiKey(String keyHash) {
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(API_KEY_PREFIX + keyHash);
            } catch (Exception e) {
                log.warn("Redis error evicting API key cache: {}", e.getMessage());
            }
        }
    }

    /**
     * Evict a tenant status from Redis (e.g. upon admin suspension/revocation/reactivation).
     */
    public void evictTenantStatus(UUID tenantId) {
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(TENANT_STATUS_PREFIX + tenantId);
            } catch (Exception e) {
                log.warn("Redis error evicting tenant status cache: {}", e.getMessage());
            }
        }
    }

    /**
     * Listen for tenant status change events to invalidate cache after DB commit.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTenantStatusChanged(TenantStatusChangedEvent event) {
        log.info("Evicting tenant status cache for tenant id={}", event.tenantId());
        evictTenantStatus(event.tenantId());
    }
}
