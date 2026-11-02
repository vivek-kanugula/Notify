package com.notify.idempotency.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notify.common.error.ErrorCode;
import com.notify.common.error.NotifyException;
import com.notify.common.model.NotificationStatus;
import com.notify.idempotency.model.CachedIdempotency;
import com.notify.idempotency.model.LockResult;
import com.notify.idempotency.model.ReplayResult;
import com.notify.notification.entity.NotificationEntity;
import com.notify.notification.entity.NotificationStatusHistoryEntity;
import com.notify.notification.repository.NotificationRepository;
import com.notify.notification.repository.NotificationStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final String IDEM_PREFIX = "idem:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final NotificationRepository notificationRepository;
    private final NotificationStatusHistoryRepository statusHistoryRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyService(
            NotificationRepository notificationRepository,
            NotificationStatusHistoryRepository statusHistoryRepository,
            @Autowired(required = false) StringRedisTemplate redisTemplate
    ) {
        this.notificationRepository = notificationRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    /**
     * Checks if the request is an idempotent replay.
     * Evaluates Redis fast-path first; falls back to authoritative PostgreSQL.
     * Throws 409 IDEMPOTENCY_IN_PROGRESS if locked in-flight.
     * Throws 422 IDEMPOTENCY_KEY_REUSED if incoming payload hash differs from stored hash.
     */
    public Optional<ReplayResult> checkReplay(UUID tenantId, String idempotencyKey, String incomingHash) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }

        String cacheKey = buildKey(tenantId, idempotencyKey);

        // 1. Fast-path Redis check
        if (redisTemplate != null) {
            try {
                String cachedJson = redisTemplate.opsForValue().get(cacheKey);
                if (cachedJson != null) {
                    CachedIdempotency cached = objectMapper.readValue(cachedJson, CachedIdempotency.class);
                    if ("IN_PROGRESS".equalsIgnoreCase(cached.status())) {
                        throw new NotifyException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                                "A request with idempotency key '" + idempotencyKey + "' is currently in progress");
                    }
                    if ("DONE".equalsIgnoreCase(cached.status())) {
                        if (cached.hash() != null && cached.hash().equals(incomingHash)) {
                            return Optional.of(new ReplayResult(cached.notificationId(), cached.acceptanceStatus()));
                        } else {
                            throw new NotifyException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                                    "Idempotency key '" + idempotencyKey + "' was already used with a different request payload");
                        }
                    }
                }
            } catch (NotifyException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Redis error during idempotency check for key {}, falling back to database: {}", cacheKey, e.getMessage());
            }
        }

        // 2. Authoritative PostgreSQL fallback
        Optional<NotificationEntity> existingOpt = notificationRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
        if (existingOpt.isPresent()) {
            NotificationEntity existing = existingOpt.get();
            if (existing.getRequestHash() != null && existing.getRequestHash().equals(incomingHash)) {
                // Reconstruct original acceptance status from initial immutable status-history entry
                NotificationStatus originalStatus = statusHistoryRepository
                        .findFirstByTenantIdAndNotificationIdOrderByOccurredAtAsc(tenantId, existing.getId())
                        .map(NotificationStatusHistoryEntity::getToStatus)
                        .orElse(existing.getScheduledAt() != null ? NotificationStatus.SCHEDULED : NotificationStatus.QUEUED);

                // Backfill Redis cache for acceleration
                recordSuccess(tenantId, idempotencyKey, incomingHash, existing.getId(), originalStatus);

                return Optional.of(new ReplayResult(existing.getId(), originalStatus));
            } else {
                throw new NotifyException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Idempotency key '" + idempotencyKey + "' was already used with a different request payload");
            }
        }

        return Optional.empty();
    }

    /**
     * Backward-compatible lock acquisition.
     */
    public String acquireInFlightLock(UUID tenantId, String idempotencyKey) {
        LockResult res = acquireInFlightLock(tenantId, idempotencyKey, null);
        if (res instanceof LockResult.Acquired acquired) {
            return acquired.token();
        }
        return null;
    }

    /**
     * Acquires an in-flight processing lock in Redis with a 60-second TTL.
     * If a concurrent request has already written a DONE entry, checks incomingHash:
     * - If hash matches: returns LockResult.Replay so original 202 is served without token deduction ().
     * - If hash differs: throws 422 IDEMPOTENCY_KEY_REUSED.
     * If another request is currently in flight:
     * - Throws 409 IDEMPOTENCY_IN_PROGRESS.
     */
    public LockResult acquireInFlightLock(UUID tenantId, String idempotencyKey, String incomingHash) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || redisTemplate == null) {
            return LockResult.acquired(null);
        }

        String cacheKey = buildKey(tenantId, idempotencyKey);
        String lockToken = UUID.randomUUID().toString();
        try {
            String inProgressJson = objectMapper.writeValueAsString(CachedIdempotency.inProgress(lockToken));
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(cacheKey, inProgressJson, LOCK_TTL);
            if (Boolean.FALSE.equals(acquired)) {
                // Key already exists in Redis; re-check if done or truly in progress
                String currentVal = redisTemplate.opsForValue().get(cacheKey);
                if (currentVal != null) {
                    try {
                        CachedIdempotency cached = objectMapper.readValue(currentVal, CachedIdempotency.class);
                        if ("DONE".equalsIgnoreCase(cached.status())) {
                            if (incomingHash != null && incomingHash.equals(cached.hash())) {
                                return LockResult.replay(new ReplayResult(cached.notificationId(), cached.acceptanceStatus()));
                            } else if (incomingHash != null) {
                                throw new NotifyException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                                        "Idempotency key '" + idempotencyKey + "' was already used with a different request payload");
                            } else {
                                return LockResult.acquired(null);
                            }
                        }
                    } catch (NotifyException e) {
                        throw e;
                    } catch (Exception e) {
                        log.warn("Failed to parse cached idempotency for key {}: {}", cacheKey, e.getMessage());
                    }
                }
                throw new NotifyException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                        "A request with idempotency key '" + idempotencyKey + "' is currently in progress");
            }
            return LockResult.acquired(lockToken);
        } catch (NotifyException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Redis error acquiring idempotency lock for key {}, continuing to database: {}", cacheKey, e.getMessage());
            return LockResult.acquired(null);
        }
    }

    /**
     * Records successful acceptance in Redis with 24h TTL.
     */
    public void recordSuccess(UUID tenantId, String idempotencyKey, String hash, UUID notificationId, NotificationStatus acceptanceStatus) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || redisTemplate == null) {
            return;
        }

        String cacheKey = buildKey(tenantId, idempotencyKey);
        try {
            CachedIdempotency cached = CachedIdempotency.done(hash, notificationId, acceptanceStatus);
            String json = objectMapper.writeValueAsString(cached);
            redisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis error caching completed idempotency for key {}: {}", cacheKey, e.getMessage());
        }
    }

    /**
     * Evicts in-flight lock upon transaction abort or validation/rendering failure.
     * Only deletes the key if status is IN_PROGRESS and the lockToken matches,
     * ensuring we never delete a newer request's lock or a completed record.
     */
    public void evictInFlightLock(UUID tenantId, String idempotencyKey, String lockToken) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || redisTemplate == null || lockToken == null) {
            return;
        }

        String cacheKey = buildKey(tenantId, idempotencyKey);
        try {
            String script = """
                local val = redis.call('get', KEYS[1])
                if val and string.find(val, '"IN_PROGRESS"') and string.find(val, ARGV[1], 1, true) then
                    return redis.call('del', KEYS[1])
                else
                    return 0
                end
            """;
            redisTemplate.execute(
                    new org.springframework.data.redis.core.script.DefaultRedisScript<>(script, Long.class),
                    java.util.Collections.singletonList(cacheKey),
                    lockToken
            );
        } catch (Exception e) {
            log.warn("Redis error evicting idempotency lock for key {}: {}", cacheKey, e.getMessage());
        }
    }

    public void evictInFlightLock(UUID tenantId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || redisTemplate == null) {
            return;
        }

        String cacheKey = buildKey(tenantId, idempotencyKey);
        try {
            String val = redisTemplate.opsForValue().get(cacheKey);
            if (val != null && val.contains("\"IN_PROGRESS\"")) {
                redisTemplate.delete(cacheKey);
            }
        } catch (Exception e) {
            log.warn("Redis error evicting idempotency lock for key {}: {}", cacheKey, e.getMessage());
        }
    }

    private String buildKey(UUID tenantId, String key) {
        return IDEM_PREFIX + tenantId + ":" + key;
    }
}
