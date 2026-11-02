package com.notify.observability.metrics;

import com.notify.common.model.Actor;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.observability.config.MetricsProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Custom {@code notify_*} meter catalog (/ ). Counters and timers are recorded through this one bean so
 * tag policy is applied in a single place: label cardinality is bounded, and the {@code tenant_id} tag is added
 * only when {@code notify.metrics.tenant-tag=true} (). No message ids, recipients, subjects, URLs or
 * secrets are ever used as labels.
 *
 * <p>Meter names omit the Prometheus {@code _total}/{@code _seconds} suffixes, which the registry appends.
 */
@Component
public class NotifyMetrics {

    private final MeterRegistry registry;
    private final boolean tenantTag;

    public NotifyMetrics(MeterRegistry registry, MetricsProperties properties) {
        this.registry = registry;
        this.tenantTag = properties.tenantTag();
    }

    public void incrementCreated(Channel channel, String priority, UUID tenantId) {
        registry.counter("notify.notifications.created",
                withTenant(Tags.of("channel", channel.name(), "priority", safe(priority)), tenantId)).increment();
    }

    public void recordTransition(NotificationStatus from, NotificationStatus to, Actor actor) {
        registry.counter("notify.notifications.transition",
                Tags.of("from_status", from == null ? "NONE" : from.name(),
                        "to_status", to.name(), "actor", actor.name())).increment();
    }

    public void incrementDeliveryAttempt(Channel channel, String provider, String resultStatus) {
        registry.counter("notify.delivery.attempts",
                Tags.of("channel", channel.name(), "provider", safe(provider), "result_status", safe(resultStatus)))
                .increment();
    }

    public void recordDeliveryDuration(Channel channel, String provider, Duration duration) {
        Timer.builder("notify.delivery.duration")
                .tags("channel", channel.name(), "provider", safe(provider))
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    public void recordE2eDuration(Channel channel, Duration duration) {
        Timer.builder("notify.delivery.e2e.duration")
                .tags("channel", channel.name())
                .register(registry)
                .record(duration);
    }

    public void incrementRetry(Channel channel) {
        registry.counter("notify.retries", Tags.of("channel", channel.name())).increment();
    }

    public void incrementDeferral(Channel channel) {
        registry.counter("notify.deferrals", Tags.of("channel", channel.name())).increment();
    }

    public void recordScheduleDrift(Duration drift) {
        Timer.builder("notify.schedule.drift")
                .register(registry)
                .record(drift);
    }

    public void incrementThrottled(String type, UUID tenantId) {
        registry.counter("notify.ratelimit.throttled", withTenant(Tags.of("type", safe(type)), tenantId)).increment();
    }

    private Tags withTenant(Tags base, UUID tenantId) {
        if (tenantTag && tenantId != null) {
            return base.and("tenant_id", tenantId.toString());
        }
        return base;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
