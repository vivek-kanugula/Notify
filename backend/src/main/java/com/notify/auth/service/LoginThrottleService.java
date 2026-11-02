package com.notify.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;

/**
 * Login throttling: 5 failures per 15 min per (email hash + IP).
 * Redis key: login:fail:{sha256(email)}:{ip}, counter with 15-min TTL.
 * Fail-open if Redis is down per CONTRACTS.md.
 */
@Service
public class LoginThrottleService {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottleService.class);
    private static final String KEY_PREFIX = "login:fail:";
    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;

    public LoginThrottleService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Check if login is throttled for the given email and IP.
     * Returns true if throttled (too many failures).
     */
    public boolean isThrottled(String email, String ip) {
        try {
            String key = buildKey(email, ip);
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                int count = Integer.parseInt(value);
                return count >= MAX_FAILURES;
            }
            return false;
        } catch (Exception e) {
            // Fail-open: if Redis is down, allow login attempt
            log.warn("Login throttle check failed (fail-open): {}", e.getMessage());
            return false;
        }
    }

    /**
     * Record a failed login attempt.
     */
    public void recordFailure(String email, String ip) {
        try {
            String key = buildKey(email, ip);
            Long count = redisTemplate.opsForValue().increment(key);
            Long ttl = redisTemplate.getExpire(key);
            if (count != null && (count == 1L || ttl == null || ttl < 0L)) {
                redisTemplate.expire(key, WINDOW);
            }
        } catch (Exception e) {
            log.warn("Failed to record login failure in Redis (fail-open): {}", e.getMessage());
        }
    }

    /**
     * Clear failure count on successful login.
     */
    public void clearFailures(String email, String ip) {
        try {
            redisTemplate.delete(buildKey(email, ip));
        } catch (Exception e) {
            log.warn("Failed to clear login failures in Redis: {}", e.getMessage());
        }
    }

    private String buildKey(String email, String ip) {
        return KEY_PREFIX + sha256(email.toLowerCase(Locale.ROOT)) + ":" + ip;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
