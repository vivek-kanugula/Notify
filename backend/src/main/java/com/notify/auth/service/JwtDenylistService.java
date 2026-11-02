package com.notify.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis JWT denylist for logout revocation.
 * Key: jwt:deny:{jti}, value: "1", TTL: remaining access-token lifetime.
 * Fail-open policy: if Redis is down, accept the token (15-min window accepted risk, /OQ2=A).
 */
@Service
public class JwtDenylistService {

    private static final Logger log = LoggerFactory.getLogger(JwtDenylistService.class);
    private static final String KEY_PREFIX = "jwt:deny:";

    private final StringRedisTemplate redisTemplate;

    public JwtDenylistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Add a JTI to the denylist with the remaining TTL of the access token.
     */
    public void denyJti(String jti, Duration remainingTtl) {
        if (remainingTtl.isNegative() || remainingTtl.isZero()) {
            return; // Token already expired, no need to denylist
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", remainingTtl);
        } catch (Exception e) {
            // Fail-open: log and continue. 15-min window accepted risk.
            log.warn("Failed to add JTI to Redis denylist (fail-open): {}", e.getMessage());
        }
    }

    /**
     * Check if a JTI is denied. Returns false (allow) if Redis is unavailable.
     */
    public boolean isDenied(String jti) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
        } catch (Exception e) {
            // Fail-open: if Redis is down, accept the token
            log.warn("Failed to check JWT denylist in Redis (fail-open): {}", e.getMessage());
            return false;
        }
    }
}
