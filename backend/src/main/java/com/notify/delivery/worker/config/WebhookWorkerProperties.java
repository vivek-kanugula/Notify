package com.notify.delivery.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tuning of the webhook delivery worker ({@code notify.worker.webhook.*}, ). The defaults match the email worker
 * so the failure policy and the health thresholds behave identically per channel; the worker stays
 * disabled unless {@code notify.worker.webhook.enabled=true} (extended).
 */
@ConfigurationProperties(prefix = "notify.worker.webhook")
public record WebhookWorkerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("notify.dispatch.webhook.v1") String topic,
        @DefaultValue("notify-webhook-delivery-workers") String groupId,
        @DefaultValue("true") boolean autoStartup,
        @DefaultValue("3s") Duration retryInterval,
        @DefaultValue("3") int retryAttempts,
        @DefaultValue("30s") Duration pauseInitial,
        @DefaultValue("300s") Duration pauseMax,
        @DefaultValue("3") int escalateAfter,
        @DefaultValue("5s") Duration healthPollInterval,
        @DefaultValue("2") int healthSuccesses,
        @DefaultValue("8s") Duration dltSendTimeout,
        @DefaultValue("1000") long lagThreshold,
        @DefaultValue("60s") Duration assignmentGrace
) implements WorkerTuning {

    @Override
    public String name() {
        return "webhook";
    }

    public WebhookWorkerProperties {
        if (retryAttempts < 0) {
            throw new IllegalArgumentException("notify.worker.webhook.retry-attempts must not be negative");
        }
        if (escalateAfter < 1) {
            throw new IllegalArgumentException("notify.worker.webhook.escalate-after must be at least 1");
        }
        if (healthSuccesses < 1) {
            throw new IllegalArgumentException("notify.worker.webhook.health-successes must be at least 1");
        }
        if (pauseMax.compareTo(pauseInitial) < 0) {
            throw new IllegalArgumentException("notify.worker.webhook.pause-max must be at least pause-initial");
        }
    }
}
