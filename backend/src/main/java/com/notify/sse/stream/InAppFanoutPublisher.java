package com.notify.sse.stream;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Publishes post-commit <b>wake-up hints</b> to {@code sse:inapp:{tenantId}} (/ , ).
 *
 * <p>A hint carries no ordering and no payload authority — only the recipient it concerns, so a listener knows which
 * streams to wake. Losing, duplicating or reordering hints is harmless: a stream drains authoritative rows from
 * PostgreSQL, and the heartbeat drain covers a hint that never arrived.
 *
 * <p>A publish failure is counted and logged without a body or a recipient value, and never fails the delivery.
 */
@Component
public class InAppFanoutPublisher {

    private static final Logger log = LoggerFactory.getLogger(InAppFanoutPublisher.class);
    public static final String CHANNEL_PREFIX = "sse:inapp:";

    private final StringRedisTemplate redis;
    private final MeterRegistry meters;

    public InAppFanoutPublisher(StringRedisTemplate redis, ObjectProvider<MeterRegistry> meters) {
        this.redis = redis;
        this.meters = meters.getIfAvailable(SimpleMeterRegistry::new);
    }

    public static String channel(UUID tenantId) {
        return CHANNEL_PREFIX + tenantId;
    }

    /** Hints are best effort by design; correctness never depends on one arriving. */
    public void publishHint(UUID tenantId, String recipientRef) {
        try {
            redis.convertAndSend(channel(tenantId), recipientRef == null ? "" : recipientRef);
        } catch (RuntimeException e) {
            meters.counter("notify.inapp.fanout_failures").increment();
            log.warn("In-app fan-out hint could not be published; the next drain will recover it ({})",
                    e.getClass().getName());
        }
    }
}
