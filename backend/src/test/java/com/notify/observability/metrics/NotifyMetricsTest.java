package com.notify.observability.metrics;

import com.notify.common.model.Actor;
import com.notify.common.model.Channel;
import com.notify.common.model.NotificationStatus;
import com.notify.observability.config.MetricsProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** / custom counters increment accurately and the tenant_id tag is gated. */
class NotifyMetricsTest {

    @Test
    @DisplayName("counters and timers record on lifecycle methods")
    void metersRecord() {
        MeterRegistry registry = new SimpleMeterRegistry();
        NotifyMetrics metrics = new NotifyMetrics(registry, new MetricsProperties(false));

        metrics.incrementCreated(Channel.EMAIL, "normal", UUID.randomUUID());
        metrics.recordTransition(NotificationStatus.QUEUED, NotificationStatus.PROCESSING, Actor.SYSTEM);
        metrics.incrementDeliveryAttempt(Channel.EMAIL, "fake", "SUCCESS");
        metrics.recordDeliveryDuration(Channel.EMAIL, "fake", Duration.ofMillis(120));
        metrics.recordE2eDuration(Channel.EMAIL, Duration.ofMillis(300));
        metrics.incrementRetry(Channel.WEBHOOK);
        metrics.incrementDeferral(Channel.WEBHOOK);
        metrics.recordScheduleDrift(Duration.ofSeconds(2));

        assertThat(registry.get("notify.notifications.created").tag("channel", "EMAIL").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("notify.notifications.transition")
                .tags("from_status", "QUEUED", "to_status", "PROCESSING", "actor", "SYSTEM").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("notify.delivery.attempts").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("notify.delivery.duration").timer().count()).isEqualTo(1L);
        assertThat(registry.get("notify.delivery.e2e.duration").timer().count()).isEqualTo(1L);
        assertThat(registry.get("notify.retries").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("notify.deferrals").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("notify.schedule.drift").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("tenant_id tag absent when tenant-tag=false, present when true")
    void tenantTagGating() {
        UUID tenant = UUID.randomUUID();

        MeterRegistry off = new SimpleMeterRegistry();
        new NotifyMetrics(off, new MetricsProperties(false)).incrementCreated(Channel.EMAIL, "n", tenant);
        assertThat(off.get("notify.notifications.created").counter().getId().getTag("tenant_id")).isNull();

        MeterRegistry on = new SimpleMeterRegistry();
        new NotifyMetrics(on, new MetricsProperties(true)).incrementCreated(Channel.EMAIL, "n", tenant);
        assertThat(on.get("notify.notifications.created").counter().getId().getTag("tenant_id"))
                .isEqualTo(tenant.toString());
    }

    @Test
    @DisplayName("incrementThrottled records counter with type and gated tenant_id")
    void throttledMeterRecords() {
        UUID tenant = UUID.randomUUID();
        MeterRegistry registry = new SimpleMeterRegistry();
        NotifyMetrics metrics = new NotifyMetrics(registry, new MetricsProperties(false));

        metrics.incrementThrottled("TENANT", tenant);
        metrics.incrementThrottled("IP", null);

        assertThat(registry.get("notify.ratelimit.throttled").tag("type", "TENANT").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("notify.ratelimit.throttled").tag("type", "IP").counter().count()).isEqualTo(1.0);
    }
}
