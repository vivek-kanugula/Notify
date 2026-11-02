package com.notify.retention.service;

import com.notify.retention.config.RetentionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;

/**
 * Deletes in-app messages older than 90 days (schema §Retention).
 *
 * <p>Chunks are selected by {@code seq}, which is this table's primary key — it has no {@code id} column.
 */
@Service
@ConditionalOnProperty(prefix = "notify.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InAppMessageRetentionService {

    private final ChunkedDeleter deleter;
    private final RetentionProperties properties;
    private final Clock clock;

    public InAppMessageRetentionService(ChunkedDeleter deleter, RetentionProperties properties, Clock clock) {
        this.deleter = deleter;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${notify.retention.inapp-messages-cron:0 25 3 * * *}")
    public void scheduledSweep() {
        sweep();
    }

    /** @return rows deleted */
    public int sweep() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(properties.inappMessages()));
        String sql = """
                DELETE FROM inapp_messages WHERE seq IN (
                    SELECT seq FROM inapp_messages WHERE created_at < ? LIMIT %d
                )
                """.formatted(deleter.batchSize());
        return deleter.sweep("inapp_messages", sql, cutoff);
    }
}
