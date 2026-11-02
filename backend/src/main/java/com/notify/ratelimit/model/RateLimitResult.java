package com.notify.ratelimit.model;

/**
 * Result of rate limit evaluation.
 */
public record RateLimitResult(
        boolean allowed,
        int limit,
        int remaining,
        long resetSeconds,
        long retryAfterSeconds,
        boolean tokenDeducted
) {
    public RateLimitResult(boolean allowed, int limit, int remaining, long resetSeconds, long retryAfterSeconds) {
        this(allowed, limit, remaining, resetSeconds, retryAfterSeconds, allowed);
    }

    public static RateLimitResult allowed(int limit, int remaining, long resetSeconds) {
        return new RateLimitResult(true, limit, remaining, resetSeconds, 0, true);
    }

    public static RateLimitResult limited(int limit, long retryAfterSeconds) {
        return new RateLimitResult(false, limit, 0, retryAfterSeconds, retryAfterSeconds, false);
    }

    public static RateLimitResult failOpen(int limit) {
        return new RateLimitResult(true, limit, limit, 0, 0, false);
    }
}
