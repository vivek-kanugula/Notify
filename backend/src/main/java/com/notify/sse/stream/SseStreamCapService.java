package com.notify.sse.stream;

import com.notify.sse.config.SseProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The cross-instance stream cap, as a Redis lease (/ , ).
 *
 * <p>A local counter cannot enforce "100 streams per tenant": two instances each admitting 100 would give the tenant
 * 200. Admission is therefore a single atomic script - prune lapsed leases, count, add - and it is the <b>only</b>
 * path that creates a member.
 *
 * <p>Three properties the scripts enforce, each of them load-bearing:
 * <ul>
 *   <li><b>one key per tenant.</b> The generation lives inside the member set as a reserved {@code +inf} member, so
 *       "the set was lost" and "its provenance was lost" are the same event by construction. A separate sentinel key
 *       could be lost independently, and a measured multi-tenant sequence showed that letting a second key vouch for
 *       a tenant admits new streams while old ones still hold authority;</li>
 *   <li><b>unknown provenance is always quarantined</b> for {@code barrier}, first use included. The absence of a
 *       Redis key is never evidence that an event never happened;</li>
 *   <li><b>refresh validates three things</b> - generation, membership, and a score strictly later than Redis's own
 *       {@code TIME}. Pruning happens during admission, so an expired member can still be present; {@code ZADD XX}
 *       alone would renew it.</li>
 * </ul>
 *
 * <p>Expiry is always judged by Redis's clock, read inside the script, so two instances with skewed clocks cannot
 * disagree about it.
 */
@Service
public class SseStreamCapService {

    private static final Logger log = LoggerFactory.getLogger(SseStreamCapService.class);
    private static final String KEY_PREFIX = "sse:streams:";
    private static final String RESERVED_PREFIX = "__gen__:";

    static final String ADMIT_SCRIPT = """
            local t    = redis.call('TIME')
            local now  = t[1] * 1000 + math.floor(t[2] / 1000)
            local resv = redis.call('ZRANGEBYSCORE', KEYS[1], '+inf', '+inf')
            if resv[1] == nil then
              local untilMs = now + tonumber(ARGV[4])
              redis.call('ZADD', KEYS[1], '+inf', '__gen__:' .. ARGV[5] .. ':' .. untilMs)
              redis.call('PEXPIRE', KEYS[1], ARGV[3])
              return {0, 'BARRIER_ARMED_UNKNOWN_PROVENANCE', untilMs - now}
            end
            local gen, barrier = string.match(resv[1], '^__gen__:(.-):(%d+)$')
            if now < tonumber(barrier) then return {0, 'BARRIER', tonumber(barrier) - now} end
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            if redis.call('ZCARD', KEYS[1]) - 1 >= tonumber(ARGV[2]) then return {0, 'CAP', 0} end
            redis.call('ZADD', KEYS[1], now + tonumber(ARGV[1]), gen .. ':' .. ARGV[6])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return {1, gen, now + tonumber(ARGV[1])}
            """;

    static final String REFRESH_SCRIPT = """
            local t    = redis.call('TIME')
            local now  = t[1] * 1000 + math.floor(t[2] / 1000)
            local resv = redis.call('ZRANGEBYSCORE', KEYS[1], '+inf', '+inf')
            if resv[1] == nil then return {0, 'SET_LOST', 0} end
            local gen = string.match(resv[1], '^__gen__:(.-):%d+$')
            if gen ~= ARGV[3] then return {0, 'GENERATION', 0} end
            local score = redis.call('ZSCORE', KEYS[1], ARGV[4])
            if score == false then return {0, 'ABSENT', 0} end
            if tonumber(score) <= now then return {0, 'EXPIRED', 0} end
            redis.call('ZADD', KEYS[1], 'XX', 'GT', now + tonumber(ARGV[1]), ARGV[4])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return {1, 'OK', now + tonumber(ARGV[1])}
            """;

    /**
     * Read-only readiness, for the token endpoint (condition C2).
     *
     * <p>It writes nothing: no member, no generation, no TTL touch. It answers the question the browser cannot ask
     * the stream - "would a connection be admitted right now, and if not for how long?" - so the refusal reaches the
     * client as readable JSON instead of as an {@code EventSource} error with no status, no {@code Retry-After} and
     * no body.
     *
     * <p>When the tenant's set carries no generation it answers {@code READY}, not a barrier. That is deliberate:
     * only an admission attempt may arm a barrier, and telling the client to wait first would mean nobody ever arms
     * it. The one attempt that follows is refused and arms it, and from then on this script reports the remaining
     * quarantine readably.
     */
    static final String READINESS_SCRIPT = """
            local t    = redis.call('TIME')
            local now  = t[1] * 1000 + math.floor(t[2] / 1000)
            local resv = redis.call('ZRANGEBYSCORE', KEYS[1], '+inf', '+inf')
            if resv[1] == nil then return {1, 'FIRST_ATTEMPT_WILL_ARM_BARRIER', 0} end
            local gen, barrier = string.match(resv[1], '^__gen__:(.-):(%d+)$')
            if now < tonumber(barrier) then return {0, 'BARRIER', tonumber(barrier) - now} end
            local live = redis.call('ZCOUNT', KEYS[1], now, '+inf') - 1
            if live >= tonumber(ARGV[1]) then return {0, 'CAP', tonumber(ARGV[2])} end
            return {1, 'READY', 0}
            """;

    static final String RELEASE_SCRIPT = """
            redis.call('ZREM', KEYS[1], ARGV[1])
            return 1
            """;

    private final StringRedisTemplate redis;
    private final SseProperties props;
    private final MonotonicTimeSource time;
    private final Clock clock;
    private final MeterRegistry meters;
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);
    private final RedisScript<List> admit = new DefaultRedisScript<>(ADMIT_SCRIPT, List.class);
    private final RedisScript<List> refresh = new DefaultRedisScript<>(REFRESH_SCRIPT, List.class);
    private final RedisScript<List> readiness = new DefaultRedisScript<>(READINESS_SCRIPT, List.class);
    private final RedisScript<Long> release = new DefaultRedisScript<>(RELEASE_SCRIPT, Long.class);
    /** Latest observed {@code Redis TIME - local clock}, in milliseconds. See {@link #observeClockOffset(long)}. */
    private final AtomicLong clockOffsetMillis = new AtomicLong();
    private final AtomicLong clockOffsetSamples = new AtomicLong();

    @Autowired
    public SseStreamCapService(StringRedisTemplate redis, SseProperties props, MonotonicTimeSource time,
                               Clock clock, ObjectProvider<MeterRegistry> meters) {
        this(redis, props, time, clock, meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** For tests: the system clock and a throwaway meter registry. */
    public SseStreamCapService(StringRedisTemplate redis, SseProperties props, MonotonicTimeSource time) {
        this(redis, props, time, Clock.systemUTC(), new SimpleMeterRegistry());
    }

    private SseStreamCapService(StringRedisTemplate redis, SseProperties props, MonotonicTimeSource time,
                                Clock clock, MeterRegistry meters) {
        this.redis = redis;
        this.props = props;
        this.time = time;
        this.clock = clock;
        this.meters = meters;
        meters.gauge("notify.inapp.redis_clock_offset_ms", clockOffsetMillis, AtomicLong::get);
    }

    /**
     * Records how far this instance's clock sits from Redis's, and warns when it exceeds the configured bound
     * (corrective cycle, finding A5).
     *
     * <p>INV-CAP does <b>not</b> depend on this number: every expiry decision is taken inside a Lua script using
     * Redis's own {@code TIME}, so two instances with skewed clocks cannot disagree about whether a lease has
     * lapsed. What {@code notify.sse.max-clock-skew} bounds is the <em>barrier</em> and the <em>safety margin</em> -
     * how long a quarantine must outlast a surviving authority. That bound was previously an assumption asserted in
     * the records and observed nowhere; it is now measured on every admission and refresh, exposed as a gauge, and
     * logged when breached, so the assumption is falsifiable in a running system instead of only in prose.
     *
     * @param redisEpochMillis Redis's own wall clock at the moment the script ran
     */
    private void observeClockOffset(long redisEpochMillis) {
        long offset = redisEpochMillis - clock.millis();
        clockOffsetMillis.set(offset);
        clockOffsetSamples.incrementAndGet();
        if (Math.abs(offset) > props.maxClockSkew().toMillis()) {
            meters.counter("notify.inapp.clock_skew_exceeded").increment();
            log.warn("Local clock differs from Redis by {} ms, beyond notify.sse.max-clock-skew of {} ms: the "
                            + "quarantine barrier is sized for that bound",
                    offset, props.maxClockSkew().toMillis());
        }
    }

    /** The latest measured {@code Redis - local} clock offset in milliseconds, for health detail and tests. */
    public long observedClockOffsetMillis() {
        return clockOffsetMillis.get();
    }

    public long clockOffsetSampleCount() {
        return clockOffsetSamples.get();
    }

    /** What an admission attempt produced. {@code retryAfterMillis} is a readiness hint for the token API (C2). */
    public sealed interface Admission {
        record Granted(SseStreamLease lease) implements Admission {
        }

        record Refused(String reason, long retryAfterMillis) implements Admission {
        }
    }

    /**
     * Admits one stream, or refuses with a reason and a retry hint.
     *
     * <p>The deadline is anchored to {@code start}, captured <b>before</b> the call, so the round trip is charged to
     * the lease; if nothing usable is left by the time the reply is processed the admission is rejected and the slot
     * is left to lapse on its own.
     */
    public Admission admit(UUID tenantId, String recipientRef, String streamId) {
        long start = time.nanos();
        List<?> result;
        try {
            result = redis.execute(admit, List.of(key(tenantId)),
                    Long.toString(props.leaseTtl().toMillis()),
                    Integer.toString(props.capPerTenant()),
                    Long.toString(props.keyTtl().toMillis()),
                    Long.toString(props.barrier().toMillis()),
                    UUID.randomUUID().toString().substring(0, 8),
                    instanceId + ":" + streamId);
        } catch (RuntimeException e) {
            // Fail closed: no admission without a verified lease (CONTRACTS §Redis).
            log.warn("SSE admission refused: Redis unavailable ({})", e.getClass().getName());
            return new Admission.Refused("REDIS_UNAVAILABLE", props.leaseTtl().toMillis());
        }
        if (result == null || result.size() < 3) {
            return new Admission.Refused("REDIS_UNAVAILABLE", props.leaseTtl().toMillis());
        }
        boolean granted = ((Number) result.get(0)).longValue() == 1L;
        String reason = String.valueOf(result.get(1));
        long value = ((Number) result.get(2)).longValue();
        if (!granted) {
            return new Admission.Refused(reason, value);
        }
        // `value` is Redis's own clock plus the lease TTL, so subtracting the TTL recovers Redis's wall clock.
        observeClockOffset(value - props.leaseTtl().toMillis());

        long deadline = start + props.servingBudget().toNanos();
        if (deadline - time.nanos() <= 0) {
            // The reply outlived the authority it granted. Serving now would run past the Redis expiry.
            log.warn("SSE admission discarded: the reply took longer than the serving budget");
            return new Admission.Refused("STALE_ADMISSION_REPLY", props.leaseTtl().toMillis());
        }
        String member = reason + ":" + instanceId + ":" + streamId;
        return new Admission.Granted(new SseStreamLease(
                tenantId, recipientRef, streamId, member, reason, value, deadline));
    }

    /** Validating refresh. Returns the extended lease, or empty when the caller must stop at its current deadline. */
    public RefreshResult refresh(SseStreamLease lease) {
        long start = time.nanos();
        List<?> result;
        try {
            result = redis.execute(refresh, List.of(key(lease.tenantId())),
                    Long.toString(props.leaseTtl().toMillis()),
                    Long.toString(props.keyTtl().toMillis()),
                    lease.generation(),
                    lease.member());
        } catch (RuntimeException e) {
            return new RefreshResult(null, "REDIS_UNAVAILABLE");
        }
        if (result == null || result.size() < 3) {
            return new RefreshResult(null, "REDIS_UNAVAILABLE");
        }
        if (((Number) result.get(0)).longValue() != 1L) {
            return new RefreshResult(null, String.valueOf(result.get(1)));
        }
        long deadline = start + props.servingBudget().toNanos();
        if (deadline - time.nanos() <= 0) {
            return new RefreshResult(null, "STALE_REFRESH_REPLY");
        }
        long redisExpiry = ((Number) result.get(2)).longValue();
        observeClockOffset(redisExpiry - props.leaseTtl().toMillis());
        return new RefreshResult(lease.withDeadline(deadline, redisExpiry), "OK");
    }

    public record RefreshResult(SseStreamLease lease, String reason) {
        public boolean renewed() {
            return lease != null;
        }
    }

    /** Whether a stream would be admitted now, and for how long to wait when it would not. */
    public record Readiness(boolean ready, String reason, long retryAfterMillis) {
    }

    /**
     * Read-only readiness for this tenant. Writes nothing, so asking is free and cannot consume a slot.
     *
     * <p>Redis being unavailable is <b>not</b> reported as "not ready with a retry hint": a token cannot be minted
     * without Redis either, and the caller already fails closed on that. It is reported as ready so that the single
     * fail-closed path stays the token mint itself, rather than two paths disagreeing.
     */
    public Readiness readiness(UUID tenantId) {
        List<?> result;
        try {
            result = redis.execute(readiness, List.of(key(tenantId)),
                    Integer.toString(props.capPerTenant()),
                    Long.toString(props.heartbeat().toMillis()));
        } catch (RuntimeException e) {
            log.debug("SSE readiness could not be read ({})", e.getClass().getName());
            return new Readiness(true, "REDIS_UNAVAILABLE", 0);
        }
        if (result == null || result.size() < 3) {
            return new Readiness(true, "REDIS_UNAVAILABLE", 0);
        }
        boolean ready = ((Number) result.get(0)).longValue() == 1L;
        return new Readiness(ready, String.valueOf(result.get(1)), ((Number) result.get(2)).longValue());
    }

    /** Clean close: the slot is reusable at once rather than after the lease lapses. */
    public void releaseQuietly(SseStreamLease lease) {
        try {
            redis.execute(release, List.of(key(lease.tenantId())), lease.member());
        } catch (RuntimeException e) {
            log.debug("Lease release failed; it will lapse on its own ({})", e.getClass().getName());
        }
    }

    /** Admitted stream count for a tenant, for tests and health detail. Excludes the reserved generation member. */
    public long admittedCount(UUID tenantId) {
        Long size = redis.opsForZSet().size(key(tenantId));
        return size == null || size == 0 ? 0 : size - 1;
    }

    public String instanceId() {
        return instanceId;
    }

    static String reservedPrefix() {
        return RESERVED_PREFIX;
    }

    private static String key(UUID tenantId) {
        return KEY_PREFIX + tenantId;
    }
}