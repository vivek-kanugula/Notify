package com.notify.sse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * SSE stream and cap-lease timings ({@code notify.sse.*}, / , ).
 *
 * <p>The values are a <b>set</b>, not independent knobs: the constructor asserts the relationships INV-CAP depends
 * on, so a test that scales one of them without the others fails here rather than producing a subtly unsafe system
 * (condition C5). Production defaults are asserted separately by {@code SsePropertiesDefaultsTest}.
 *
 * <ul>
 *   <li>{@code barrier >= leaseTtl + maxClockSkew} - a quarantine must outlast the longest surviving authority;</li>
 *   <li>{@code leaseTtl - safetyMargin > heartbeat} - a stream must get at least one refresh attempt before its
 *       local deadline;</li>
 *   <li>{@code safetyMargin > maxClockSkew} - the local stop must precede the Redis reclaim;</li>
 *   <li>{@code keyTtl > maxLifetime + leaseTtl} - the member set must outlive the longest possible stream.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "notify.sse")
public record SseProperties(
        @DefaultValue("90s") Duration leaseTtl,
        @DefaultValue("25s") Duration heartbeat,
        @DefaultValue("8s") Duration safetyMargin,
        @DefaultValue("5s") Duration maxClockSkew,
        @DefaultValue("95s") Duration barrier,
        @DefaultValue("30m") Duration maxLifetime,
        @DefaultValue("24h") Duration keyTtl,
        @DefaultValue("60s") Duration tokenTtl,
        @DefaultValue("100") int capPerTenant,
        @DefaultValue("200") int maxStreamsPerInstance,
        /**
         * Waiting tasks each bounded stream pool may hold.
         *
         * <p>This is the explicit bound finding R2 asked for. {@code Executors.newFixedThreadPool} pairs a fixed
         * thread count with an <b>unbounded</b> queue, and the per-stream in-flight marker does not supply a bound
         * either: it stops duplicates for a live stream, but a task queued for a stream that then closed used to
         * stay queued, so churn accumulated work without any limit following from the registry size. Saturation
         * aborts the submit rather than running it on the caller's thread, and costs at most a delayed keep-alive
         * or one skipped drain - never a lost message, because PostgreSQL stays authoritative and the dirty flag
         * survives.
         */
        @DefaultValue("64") int queueCapacity,
        /**
         * Threads allowed to write to a client or drain PostgreSQL for a stream.
         *
         * <p>Separate from the timer pool on purpose: a stalled SSE write holds its thread for as long as the
         * container takes to give up (measured at about 59 s on this stack), and the watchdog that enforces the
         * serving deadline must fire regardless. Eight is a development/demo default - it bounds how many stalled
         * clients can delay other streams' keep-alives, never whether their authority expires on time.
         */
        @DefaultValue("8") int dispatchThreads
) {

    /**
     * The {@code @DefaultValue} above, as a constant tests can assert against.
     *
     * <p>Intentionally <b>not</b> paired with a convenience constructor that omits {@code dispatchThreads}: a
     * {@code @ConfigurationProperties} record binds through its canonical constructor, and a second constructor makes
     * Spring fall back to looking for a no-argument one ({@code No default constructor found}). Every call site
     * passes the full set.
     */
    public static final int DEFAULT_DISPATCH_THREADS = 8;

    public SseProperties {
        requirePositive(leaseTtl, "lease-ttl");
        requirePositive(heartbeat, "heartbeat");
        requirePositive(safetyMargin, "safety-margin");
        requirePositive(barrier, "barrier");
        requirePositive(maxLifetime, "max-lifetime");
        requirePositive(keyTtl, "key-ttl");
        requirePositive(tokenTtl, "token-ttl");
        if (capPerTenant < 1 || maxStreamsPerInstance < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("notify.sse cap, per-instance limit and queue capacity must be positive");
        }
        if (dispatchThreads < 1) {
            throw new IllegalArgumentException("notify.sse.dispatch-threads must be positive");
        }
        if (barrier.compareTo(leaseTtl.plus(maxClockSkew)) < 0) {
            throw new IllegalArgumentException(
                    "notify.sse.barrier must be at least lease-ttl + max-clock-skew; shortening the barrier alone "
                            + "would let a new admission overlap a stream that still holds authority");
        }
        if (leaseTtl.minus(safetyMargin).compareTo(heartbeat) <= 0) {
            throw new IllegalArgumentException(
                    "notify.sse.lease-ttl minus safety-margin must exceed heartbeat, or a stream would stop before "
                            + "its first refresh attempt");
        }
        if (safetyMargin.compareTo(maxClockSkew) <= 0) {
            throw new IllegalArgumentException(
                    "notify.sse.safety-margin must exceed max-clock-skew, or the local stop could follow the Redis "
                            + "reclaim of the same slot");
        }
        if (keyTtl.compareTo(maxLifetime.plus(leaseTtl)) <= 0) {
            throw new IllegalArgumentException(
                    "notify.sse.key-ttl must exceed max-lifetime + lease-ttl, or a live stream's membership set "
                            + "could expire underneath it");
        }
    }

    /** Usable local authority granted by one admission or refresh: the budget minus the safety margin. */
    public Duration servingBudget() {
        return leaseTtl.minus(safetyMargin);
    }

    /** The catch-up budget, from {@code notify.inapp.catch-up-limit}'s contracted value of 100. */
    public int catchUpLimit() {
        return 100;
    }

    /** One row more than the budget: the extra row is the {@code resync} probe (). */
    public int catchUpLimitProbe() {
        return catchUpLimit() + 1;
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("notify.sse." + name + " must be positive");
        }
    }
}