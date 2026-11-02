package com.notify.delivery.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Provider resilience settings ({@code notify.resilience.*}, / , ).
 *
 * <p><b>Disabled by default.</b> Adding the dependency and this configuration cannot change the behaviour of an
 * existing deployment until {@code enabled} is set, and the acceptance run sets it explicitly - the same stance
 * every worker in this project takes.
 *
 * <p><b>On the bulkhead defaults.</b> Three concurrent calls per channel. The plan originally proposed a constraint
 * that a bulkhead must be at least the channel's listener concurrency; that was **withdrawn** in . A smaller
 * positive provider limit is legitimate, and lower capacity simply produces intentional local deferrals, which cost
 * no attempt. So nothing here couples the bulkhead to listener concurrency.
 *
 * <p><b>On the Resend rate limit.</b> The default of 10 permits per second is a conservative placeholder and is
 * explicitly <b>not</b> verified against Resend's published or account limits; it must not be described as
 * provider-safe until it is. The limiter is <b>per instance</b>: it bounds what one application instance sends, not
 * what a cluster sends, because it holds no shared state. A deployment with several instances must size it
 * accordingly.
 *
 * <p><b>On the host registry.</b> Breakers are keyed by webhook <b>host</b> (, ), so two tenants with
 * endpoints on the same host share one. The registry is bounded and evicts least-recently-used entries, which also
 * bounds the metric cardinality derived from it.
 */
@ConfigurationProperties(prefix = "notify.resilience")
public record ResilienceProperties(
        @DefaultValue("false") boolean enabled,
        /** Existing, unchanged meaning and default: deferrals before a notification falls back to a real retry. */
        @DefaultValue("50") int maxDeferrals,
        @DefaultValue Breaker breaker,
        @DefaultValue Bulkhead bulkhead,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue HostRegistry hostRegistry,
        /**
         * Upper bound on a receiver-supplied {@code Retry-After} used as a {@code next_retry_at} floor (). A
         * larger value is clamped, never rejected, so a receiver cannot park work indefinitely.
         */
        @DefaultValue("5m") Duration maxRetryAfter
) {

    public ResilienceProperties {
        if (maxDeferrals < 1) {
            throw new IllegalArgumentException("notify.resilience.max-deferrals must be positive");
        }
        if (maxRetryAfter == null || maxRetryAfter.isNegative() || maxRetryAfter.isZero()) {
            throw new IllegalArgumentException("notify.resilience.max-retry-after must be positive");
        }
    }

    public record Breaker(
            @DefaultValue("50") int failureRateThresholdPercent,
            @DefaultValue("20") int slidingWindowSize,
            @DefaultValue("10") int minimumCalls,
            @DefaultValue("30s") Duration waitInOpenState,
            @DefaultValue("3") int halfOpenPermittedCalls
    ) {
        public Breaker {
            if (failureRateThresholdPercent < 1 || failureRateThresholdPercent > 100) {
                throw new IllegalArgumentException(
                        "notify.resilience.breaker.failure-rate-threshold-percent must be between 1 and 100");
            }
            if (slidingWindowSize < 5) {
                throw new IllegalArgumentException("notify.resilience.breaker.sliding-window-size must be at least 5");
            }
            if (minimumCalls < 1 || minimumCalls > slidingWindowSize) {
                throw new IllegalArgumentException(
                        "notify.resilience.breaker.minimum-calls must be between 1 and the sliding window size, or "
                                + "the breaker could never decide");
            }
            if (waitInOpenState == null || waitInOpenState.isZero() || waitInOpenState.isNegative()) {
                throw new IllegalArgumentException("notify.resilience.breaker.wait-in-open-state must be positive");
            }
            if (halfOpenPermittedCalls < 1) {
                throw new IllegalArgumentException(
                        "notify.resilience.breaker.half-open-permitted-calls must be positive");
            }
        }
    }

    /** Concurrent provider calls allowed per channel. IN_APP is absent on purpose: it calls no provider. */
    public record Bulkhead(
            @DefaultValue("3") int email,
            @DefaultValue("3") int webhook
    ) {
        public Bulkhead {
            if (email < 1 || webhook < 1) {
                throw new IllegalArgumentException(
                        "notify.resilience.bulkhead limits must be positive; a zero limit would defer every call");
            }
        }
    }

    /**
     * Outbound rate limit for the Resend provider, <b>per instance</b>. {@code permits} of zero disables it.
     *
     * <p>No webhook rate limit exists by default and none is configured here: receivers are tenant-owned and
     * heterogeneous, and a global webhook limit would couple unrelated tenants.
     */
    public record RateLimit(
            @DefaultValue("10") int resendPermits,
            @DefaultValue("1s") Duration resendPeriod
    ) {
        public RateLimit {
            if (resendPermits < 0) {
                throw new IllegalArgumentException("notify.resilience.rate-limit.resend-permits must not be negative");
            }
            if (resendPeriod == null || resendPeriod.isZero() || resendPeriod.isNegative()) {
                throw new IllegalArgumentException("notify.resilience.rate-limit.resend-period must be positive");
            }
        }

        public boolean limitsResend() {
            return resendPermits > 0;
        }
    }

    public record HostRegistry(
            @DefaultValue("256") int maxHosts,
            @DefaultValue("30m") Duration idleExpiry
    ) {
        public HostRegistry {
            if (maxHosts < 16) {
                throw new IllegalArgumentException("notify.resilience.host-registry.max-hosts must be at least 16");
            }
            if (idleExpiry == null || idleExpiry.isZero() || idleExpiry.isNegative()) {
                throw new IllegalArgumentException(
                        "notify.resilience.host-registry.idle-expiry must be positive");
            }
        }
    }

    /**
     * Asserted here rather than left to a comment: an idle host must not be evicted while its breaker could still
     * be open, because eviction resets a breaker to closed and would quietly readmit traffic to a failing host.
     */
    public boolean idleExpiryOutlivesAnOpenBreaker() {
        return hostRegistry.idleExpiry().compareTo(breaker.waitInOpenState()) > 0;
    }
}
