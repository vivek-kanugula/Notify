package com.notify.delivery.retry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Delivery retry backoff ({@code notify.retry.*}, TRD section 9): {@code min(max, base * 2^(attempt-1))} with
 * +/- {@code jitter}. Defaults 30 s / 15 min / 0.2; {@code application-local.yml} shortens them for demos.
 */
@ConfigurationProperties(prefix = "notify.retry")
public record RetryProperties(
        @DefaultValue("30s") Duration baseDelay,
        @DefaultValue("15m") Duration maxDelay,
        @DefaultValue("0.2") double jitter
) {
    public RetryProperties {
        if (baseDelay == null || baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("notify.retry.base-delay must be positive");
        }
        if (maxDelay == null || maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("notify.retry.max-delay must be at least base-delay");
        }
        if (jitter < 0 || jitter >= 1) {
            throw new IllegalArgumentException("notify.retry.jitter must be in [0, 1)");
        }
    }
}
