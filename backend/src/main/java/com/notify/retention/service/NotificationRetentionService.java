package com.notify.retention.service;

import com.notify.retention.config.RetentionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;

/**
 * Deletes finished notifications 90 days past completion, together with their children (schema §Retention).
 *
 * <p><b>Two fences, both load-bearing.</b>
 * <ul>
 *   <li><b>Terminal statuses only</b> — {@code DELIVERED}, {@code FAILED}, {@code CANCELLED} — with a
 *       non-null {@code completed_at} past the cutoff. {@code DLQ} is deliberately absent from that list: a
 *       dead-lettered notification is still actionable, and {@code dead_letter_entries} keeps its own
 *       180-day window.</li>
 *   <li><b>Any notification linked to an {@code OPEN} dead-letter entry is exempt.</b> This is not belt and
 *       braces: {@code dead_letter_entries} holds a composite FK to {@code notifications} with
 *       {@code ON DELETE CASCADE}, so deleting such a notification would silently destroy an open DLQ entry
 *       an operator has not yet replayed or discarded.</li>
 * </ul>
 *
 * <p>Children ({@code delivery_attempts}, {@code notification_status_history}, {@code inapp_messages},
 * {@code dead_letter_entries}) are removed by those same cascades, so no separate child sweep is needed.
 */
@Service
@ConditionalOnProperty(prefix = "notify.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NotificationRetentionService {

    private final ChunkedDeleter deleter;
    private final RetentionProperties properties;
    private final Clock clock;

    public NotificationRetentionService(ChunkedDeleter deleter, RetentionProperties properties, Clock clock) {
        this.deleter = deleter;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${notify.retention.notifications-cron:0 35 3 * * *}")
    public void scheduledSweep() {
        sweep();
    }

    /** @return notifications deleted (their children go with them through the cascades) */
    public int sweep() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(properties.notifications()));
        String sql = """
                DELETE FROM notifications WHERE id IN (
                    SELECT n.id FROM notifications n
                    WHERE n.status IN ('DELIVERED', 'FAILED', 'CANCELLED')
                      AND n.completed_at IS NOT NULL
                      AND n.completed_at < ?
                      AND NOT EXISTS (
                          SELECT 1 FROM dead_letter_entries d
                          WHERE d.tenant_id = n.tenant_id AND d.notification_id = n.id AND d.status = 'OPEN'
                      )
                    LIMIT %d
                )
                """.formatted(deleter.batchSize());
        return deleter.sweep("notifications", sql, cutoff);
    }
}
