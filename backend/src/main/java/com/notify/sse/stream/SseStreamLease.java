package com.notify.sse.stream;

import java.util.UUID;

/**
 * One admitted stream's lease (/ , ).
 *
 * <p>{@code servingDeadlineNanos} is anchored to the monotonic instant captured <b>before</b> the Redis request, so
 * the round trip is charged to the lease; a reply whose usable authority is already spent is rejected instead of
 * granting serving time past the Redis expiry. {@code generation} is the tenant set's provenance marker: a refresh
 * carrying a stale generation can never extend this lease.
 */
public record SseStreamLease(
        UUID tenantId,
        String recipientRef,
        String streamId,
        String member,
        String generation,
        long redisExpiryMs,
        long servingDeadlineNanos
) {

    public boolean expired(MonotonicTimeSource time) {
        return time.nanos() - servingDeadlineNanos >= 0;
    }

    public long remainingMillis(MonotonicTimeSource time) {
        return (servingDeadlineNanos - time.nanos()) / 1_000_000L;
    }

    public SseStreamLease withDeadline(long newDeadlineNanos, long newRedisExpiryMs) {
        // Deadlines never move backwards: a late or out-of-order reply cannot shorten or revive authority.
        long deadline = newDeadlineNanos - servingDeadlineNanos > 0 ? newDeadlineNanos : servingDeadlineNanos;
        return new SseStreamLease(tenantId, recipientRef, streamId, member, generation,
                Math.max(redisExpiryMs, newRedisExpiryMs), deadline);
    }
}