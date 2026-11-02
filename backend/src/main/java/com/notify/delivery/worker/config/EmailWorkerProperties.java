package com.notify.delivery.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tuning of the email delivery worker ({@code notify.worker.email.*}). Defaults follow plan sections 8.4 and 9;
 * the worker itself stays disabled unless {@code notify.worker.email.enabled=true} ().
 */
@ConfigurationProperties(prefix = "notify.worker.email")
public record EmailWorkerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("notify.dispatch.email.v1") String topic,
        @DefaultValue("notify-email-delivery-workers") String groupId,
        @DefaultValue("true") boolean autoStartup,
        /* container retry: interval between in-place retries and their count (categories that retry) */
        @DefaultValue("3s") Duration retryInterval,
        @DefaultValue("3") int retryAttempts,
        /* timed partition pause with escalation (exhausted contention / ordering / broker-transient) */
        @DefaultValue("30s") Duration pauseInitial,
        @DefaultValue("300s") Duration pauseMax,
        @DefaultValue("3") int escalateAfter,
        /* connectivity pause: resume after N consecutive successful SELECT 1 probes */
        @DefaultValue("5s") Duration healthPollInterval,
        @DefaultValue("2") int healthSuccesses,
        /* DLT send bound used by the dead-letter recoverer */
        @DefaultValue("8s") Duration dltSendTimeout,
        /* health indicator thresholds (): consumer lag and partition assignment */
        @DefaultValue("1000") long lagThreshold,
        @DefaultValue("60s") Duration assignmentGrace
) implements WorkerTuning {

    @Override
    public String name() {
        return "email";
    }

    public EmailWorkerProperties {
        if (retryAttempts < 0) {
            throw new IllegalArgumentException("notify.worker.email.retry-attempts must not be negative");
        }
        if (escalateAfter < 1) {
            throw new IllegalArgumentException("notify.worker.email.escalate-after must be at least 1");
        }
        if (healthSuccesses < 1) {
            throw new IllegalArgumentException("notify.worker.email.health-successes must be at least 1");
        }
        if (pauseMax.compareTo(pauseInitial) < 0) {
            throw new IllegalArgumentException("notify.worker.email.pause-max must be at least pause-initial");
        }
    }
}
