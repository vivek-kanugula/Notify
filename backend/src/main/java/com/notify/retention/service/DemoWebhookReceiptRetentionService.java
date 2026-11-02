package com.notify.retention.service;

import com.notify.retention.config.RetentionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;

/**
 * Keeps the public demo receiver's receipt table small: 7 days by age, and a hard row cap (schema
 * §Retention, "7 days (cap 2 000 rows)"). Runs hourly because the demo surface is the one a visitor can
 * drive as fast as they like.
 *
 * <p>The age column is {@code received_at}; this table has no {@code created_at}.
 */
@Service
@ConditionalOnProperty(prefix = "notify.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DemoWebhookReceiptRetentionService {

    private final ChunkedDeleter deleter;
    private final RetentionProperties properties;
    private final Clock clock;

    public DemoWebhookReceiptRetentionService(ChunkedDeleter deleter, RetentionProperties properties, Clock clock) {
        this.deleter = deleter;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${notify.retention.demo-webhook-receipts-cron:0 40 * * * *}")
    public void scheduledSweep() {
        sweep();
    }

    /** @return rows deleted by both the age sweep and the row cap */
    public int sweep() {
        return sweepByAge() + sweepByRowCap();
    }

    public int sweepByAge() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(properties.demoWebhookReceipts()));
        String sql = """
                DELETE FROM demo_webhook_receipts WHERE id IN (
                    SELECT id FROM demo_webhook_receipts WHERE received_at < ? LIMIT %d
                )
                """.formatted(deleter.batchSize());
        return deleter.sweep("demo_webhook_receipts (age)", sql, cutoff);
    }

    /** Keeps the newest {@code demoWebhookReceiptMaxRows} receipts and drops everything older. */
    public int sweepByRowCap() {
        String sql = """
                DELETE FROM demo_webhook_receipts WHERE id IN (
                    SELECT id FROM demo_webhook_receipts
                    ORDER BY received_at DESC, id DESC
                    OFFSET %d LIMIT %d
                )
                """.formatted(properties.demoWebhookReceiptMaxRows(), deleter.batchSize());
        return deleter.sweep("demo_webhook_receipts (row cap)", sql);
    }
}
