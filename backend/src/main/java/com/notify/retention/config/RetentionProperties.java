package com.notify.retention.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Retention and housekeeping settings ({@code notify.retention.*}) for / -3.
 *
 * <p>Windows come from {@code docs/05_BACKEND_SCHEMA.md} §Retention. {@code enabled} defaults to {@code true}
 * so a deployment keeps its tables bounded without extra configuration; the {@code test} profile sets it to
 * {@code false} so unrelated suites never have fixture rows swept out from under them.
 *
 * <p>{@code batchSize} and {@code maxIterationsPerSweep} together bound one sweep: deletions are chunked so a
 * long-overdue table cannot take a single enormous transaction and exhaust locks.
 */
@ConfigurationProperties(prefix = "notify.retention")
public record RetentionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("500") int batchSize,
        @DefaultValue("200") int maxIterationsPerSweep,
        @DefaultValue("30d") Duration refreshTokens,
        @DefaultValue("7d") Duration demoWebhookReceipts,
        @DefaultValue("2000") int demoWebhookReceiptMaxRows,
        @DefaultValue("90d") Duration inappMessages,
        @DefaultValue("90d") Duration notifications,
        @DefaultValue("180d") Duration deadLetterEntries
) {

    public RetentionProperties {
        if (batchSize < 1) {
            throw new IllegalStateException("notify.retention.batch-size must be >= 1, got " + batchSize);
        }
        if (maxIterationsPerSweep < 1) {
            throw new IllegalStateException(
                    "notify.retention.max-iterations-per-sweep must be >= 1, got " + maxIterationsPerSweep);
        }
        if (demoWebhookReceiptMaxRows < 0) {
            throw new IllegalStateException("notify.retention.demo-webhook-receipt-max-rows must be >= 0, got "
                    + demoWebhookReceiptMaxRows);
        }
    }
}
