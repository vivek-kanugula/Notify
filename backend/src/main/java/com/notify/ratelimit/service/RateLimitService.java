package com.notify.ratelimit.service;

import com.notify.observability.metrics.NotifyMetrics;
import com.notify.ratelimit.error.RateLimitException;
import com.notify.ratelimit.model.RateLimitResult;
import com.notify.tenant.entity.TenantEntity;
import com.notify.tenant.repository.TenantRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final String RL_PREFIX = "rl:";
    private static final String RL_IP_PREFIX = "rl:ip:";

    private final TenantRepository tenantRepository;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final NotifyMetrics notifyMetrics;
    private DefaultRedisScript<List> rateLimitScript;
    private DefaultRedisScript<List> ipRateLimitScript;
    private DefaultRedisScript<List> refundScript;

    public RateLimitService(
            TenantRepository tenantRepository,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            Clock clock
    ) {
        this(tenantRepository, redisTemplate, clock, null);
    }

    @Autowired
    public RateLimitService(
            TenantRepository tenantRepository,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            Clock clock,
            @Autowired(required = false) NotifyMetrics notifyMetrics
    ) {
        this.tenantRepository = tenantRepository;
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.notifyMetrics = notifyMetrics;
    }

    @PostConstruct
    public void init() {
        rateLimitScript = new DefaultRedisScript<>();
        rateLimitScript.setLocation(new ClassPathResource("scripts/ratelimit.lua"));
        rateLimitScript.setResultType(List.class);

        ipRateLimitScript = new DefaultRedisScript<>();
        ipRateLimitScript.setLocation(new ClassPathResource("scripts/ip_ratelimit.lua"));
        ipRateLimitScript.setResultType(List.class);

        refundScript = new DefaultRedisScript<>();
        refundScript.setLocation(new ClassPathResource("scripts/refund.lua"));
        refundScript.setResultType(List.class);
    }

    /**
     * Consumes 1 token for the specified tenant using the Redis Lua token bucket.
     * Fails open if Redis is unavailable per decision .
     * Throws RateLimitException (429) if exhausted.
     */
    public RateLimitResult consumeToken(UUID tenantId) {
        int capacity = getTenantRateLimit(tenantId);

        if (redisTemplate == null) {
            log.warn("Redis unavailable, failing open for tenant {}", tenantId);
            return RateLimitResult.failOpen(capacity);
        }

        try {
            double refillRate = (double) capacity / 60.0;
            long nowEpochSeconds = clock.instant().getEpochSecond();
            List<String> keys = Collections.singletonList(RL_PREFIX + tenantId);

            List<?> result = redisTemplate.execute(
                    rateLimitScript,
                    keys,
                    String.valueOf(capacity),
                    String.valueOf(refillRate),
                    String.valueOf(nowEpochSeconds),
                    "1", // 1 token requested
                    "120" // 120s idle TTL
            );

            if (result != null && result.size() >= 5) {
                long allowed = ((Number) result.get(0)).longValue();
                int limit = ((Number) result.get(1)).intValue();
                int remaining = ((Number) result.get(2)).intValue();
                long resetSeconds = ((Number) result.get(3)).longValue();
                long retryAfter = ((Number) result.get(4)).longValue();

                if (allowed == 1) {
                    return RateLimitResult.allowed(limit, remaining, resetSeconds);
                } else {
                    if (notifyMetrics != null) {
                        notifyMetrics.incrementThrottled("TENANT", tenantId);
                    }
                    throw new RateLimitException(retryAfter > 0 ? retryAfter : 1, limit, 0, resetSeconds);
                }
            }

            return RateLimitResult.failOpen(capacity);
        } catch (RateLimitException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Redis error executing token bucket for tenant {}, failing open: {}", tenantId, e.getMessage());
            return RateLimitResult.failOpen(capacity);
        }
    }

    /**
     * Checks rate limit for unauthenticated IP-scoped requests (e.g. login/signup)
     * using an atomic Redis Lua operation with TTL repair.
     */
    public void checkIpLimit(String ip, String route, int limitPerMinute) {
        if (redisTemplate == null || ip == null || ip.isBlank()) {
            return;
        }

        String key = RL_IP_PREFIX + ip + ":" + route;
        try {
            @SuppressWarnings("unchecked")
            List<?> result = redisTemplate.execute(
                    ipRateLimitScript,
                    List.of(key),
                    "60"
            );

            if (result != null && result.size() >= 2) {
                long count = ((Number) result.get(0)).longValue();
                long ttl = ((Number) result.get(1)).longValue();
                if (count > limitPerMinute) {
                    if (notifyMetrics != null) {
                        notifyMetrics.incrementThrottled("IP", null);
                    }
                    throw new RateLimitException((int) Math.max(1, ttl), limitPerMinute, 0, (int) Math.max(1, ttl));
                }
            }
        } catch (RateLimitException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Redis error evaluating IP rate limit for key {}, failing open: {}", key, e.getMessage());
        }
    }

    private int getTenantRateLimit(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .map(TenantEntity::getRateLimitPerMinute)
                .orElse(100);
    }

    /**
     * Refunds 1 token to the tenant bucket (capped at tenant capacity).
     * Used when a request deducted a token but subsequently resolved to a verified
     * idempotent replay via a database unique-index conflict, ensuring net zero quota
     * deduction per .
     */
    public void refundToken(UUID tenantId) {
        if (redisTemplate == null) {
            return;
        }

        int capacity = getTenantRateLimit(tenantId);
        try {
            double refillRate = (double) capacity / 60.0;
            long nowEpochSeconds = clock.instant().getEpochSecond();
            List<String> keys = Collections.singletonList(RL_PREFIX + tenantId);

            redisTemplate.execute(
                    refundScript,
                    keys,
                    String.valueOf(capacity),
                    String.valueOf(refillRate),
                    String.valueOf(nowEpochSeconds),
                    "1", // 1 token refunded
                    "120" // 120s idle TTL
            );
        } catch (Exception e) {
            log.warn("Redis error refunding token for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    /**
     * Inspects remaining tokens in Redis for test verification.
     */
    public int getRemainingTokens(UUID tenantId) {
        if (redisTemplate == null) {
            return getTenantRateLimit(tenantId);
        }
        String key = RL_PREFIX + tenantId;
        List<Object> data = redisTemplate.opsForHash().multiGet(key, List.of("tokens", "last_updated"));
        if (data == null || data.isEmpty() || data.get(0) == null) {
            return getTenantRateLimit(tenantId);
        }
        try {
            double tokens = Double.parseDouble(data.get(0).toString());
            return (int) Math.floor(tokens);
        } catch (Exception e) {
            return getTenantRateLimit(tenantId);
        }
    }
}
